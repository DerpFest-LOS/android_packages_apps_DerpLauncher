/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.quickstep.fallback.window

import android.animation.AnimatorSet
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.LocusId
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.window.RemoteTransition
import com.android.launcher3.BaseActivity
import com.android.launcher3.LauncherAnimationRunner
import com.android.launcher3.LauncherAnimationRunner.RemoteAnimationFactory
import com.android.launcher3.R
import com.android.launcher3.compat.AccessibilityManagerCompat
import com.android.launcher3.statehandlers.DesktopVisibilityController
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.statemanager.StateManager.AtomicAnimationFactory
import com.android.launcher3.statemanager.StatefulContainer
import com.android.launcher3.taskbar.TaskbarUIController
import com.android.launcher3.testing.shared.TestProtocol.OVERVIEW_STATE_ORDINAL
import com.android.launcher3.util.ContextTracker
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.RunnableList
import com.android.launcher3.util.SystemUiController
import com.android.launcher3.views.BaseDragLayer
import com.android.launcher3.views.ScrimView
import com.android.quickstep.FallbackWindowInterface
import com.android.quickstep.OverviewComponentObserver
import com.android.quickstep.RecentsAnimationCallbacks
import com.android.quickstep.RecentsAnimationCallbacks.RecentsAnimationListener
import com.android.quickstep.RecentsAnimationController
import com.android.quickstep.RecentsModel
import com.android.quickstep.RemoteAnimationTargets
import com.android.quickstep.SystemUiProxy
import com.android.quickstep.fallback.FallbackRecentsStateController
import com.android.quickstep.fallback.FallbackRecentsView
import com.android.quickstep.fallback.RecentsDragLayer
import com.android.quickstep.fallback.RecentsState
import com.android.quickstep.fallback.RecentsState.BACKGROUND_APP
import com.android.quickstep.fallback.RecentsState.BG_LAUNCHER
import com.android.quickstep.fallback.RecentsState.DEFAULT
import com.android.quickstep.fallback.RecentsState.HOME
import com.android.quickstep.fallback.RecentsState.MODAL_TASK
import com.android.quickstep.fallback.RecentsState.OVERVIEW_SPLIT_SELECT
import com.android.quickstep.util.RecentsAtomicAnimationFactory
import com.android.quickstep.util.RecentsWindowProtoLogProxy
import com.android.quickstep.util.SplitSelectStateController
import com.android.quickstep.util.TISBindHelper
import com.android.quickstep.views.MemInfoView
import com.android.quickstep.views.OverviewActionsView
import com.android.quickstep.views.RecentsView
import com.android.quickstep.views.RecentsViewContainer
import com.android.systemui.shared.recents.model.ThumbnailData
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import java.util.function.Predicate

/**
 * Class that will manage RecentsView lifecycle within a window and interface correctly where
 * needed. This allows us to run RecentsView in a window where needed.
 *
 * todo: b/365776320,b/365777482
 *
 * To add new protologs, see [RecentsWindowProtoLogProxy]. To enable logging to logcat, see
 * [QuickstepProtoLogGroup.Constants.DEBUG_RECENTS_WINDOW]
 */
class RecentsWindowManager(context: Context) :
    RecentsWindowContext(context), RecentsViewContainer, StatefulContainer<RecentsState> {

    companion object {
        private const val HOME_APPEAR_DURATION: Long = 250
        private const val TAG = "RecentsWindowManager"

        class RecentsWindowTracker : ContextTracker<RecentsWindowManager?>() {
            override fun isHomeStarted(context: RecentsWindowManager?): Boolean {
                return true
            }
        }

        @JvmStatic val recentsWindowTracker = RecentsWindowTracker()
    }

    protected var recentsView: FallbackRecentsView<RecentsWindowManager>? = null
    private val windowContext: Context = createWindowContext(TYPE_APPLICATION_OVERLAY, null)
    private val windowManager: WindowManager =
        windowContext.getSystemService(WindowManager::class.java)!!
    private var layoutInflater: LayoutInflater = LayoutInflater.from(this).cloneInContext(this)
    private var stateManager: StateManager<RecentsState, RecentsWindowManager> =
        StateManager<RecentsState, RecentsWindowManager>(this, RecentsState.BG_LAUNCHER)
    private var mSystemUiController: SystemUiController? = null

    private var dragLayer: RecentsDragLayer<RecentsWindowManager>? = null
    private var windowView: View? = null
    private var actionsView: OverviewActionsView<*>? = null
    private var scrimView: ScrimView? = null
    private var memInfoView: MemInfoView? = null

    private var callbacks: RecentsAnimationCallbacks? = null

    private var taskbarUIController: TaskbarUIController? = null
    private var tisBindHelper: TISBindHelper = TISBindHelper(this) {}

    // Callback array that corresponds to events defined in @ActivityEvent
    private val mEventCallbacks =
        listOf(RunnableList(), RunnableList(), RunnableList(), RunnableList())
    private var onInitListener: Predicate<Boolean>? = null

    private val taskStackChangeListener =
        object : TaskStackChangeListener {
            override fun onTaskMovedToFront(taskId: Int) {
                if ((isShowing() && isInState(DEFAULT))) {
                    // handling state where we end recents animation by swiping livetile away
                    // TODO: animate this switch.
                    cleanupRecentsWindow()
                }
            }
        }

    private val recentsAnimationListener =
        object : RecentsAnimationListener {
            override fun onRecentsAnimationCanceled(thumbnailDatas: HashMap<Int, ThumbnailData>) {
                recentAnimationStopped()
            }

            override fun onRecentsAnimationFinished(controller: RecentsAnimationController) {
                recentAnimationStopped()
            }
        }

    init {
        FallbackWindowInterface.init(this)
        TaskStackChangeListeners.getInstance().registerTaskStackListener(taskStackChangeListener)
    }

    override fun destroy() {
        super.destroy()
        cleanupRecentsWindow()
        FallbackWindowInterface.getInstance()?.destroy()
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(taskStackChangeListener)
        callbacks?.removeListener(recentsAnimationListener)
        recentsWindowTracker.onContextDestroyed(this)
    }

    override fun startHome() {
        startHome(/* finishRecentsAnimation= */ true)
    }

    fun startHome(finishRecentsAnimation: Boolean) {
        val recentsView: RecentsView<*, *> = getOverviewPanel()

        if (!finishRecentsAnimation) {
            recentsView.switchToScreenshot(/* onFinishRunnable= */ null)
            startHomeInternal()
            return
        }
        recentsView.switchToScreenshot {
            recentsView.finishRecentsAnimation(/* toRecents= */ true) { startHomeInternal() }
        }
    }

    private fun startHomeInternal() {
        val runner = LauncherAnimationRunner(mainThreadHandler, mAnimationToHomeFactory, true)
        val options =
            ActivityOptions.makeRemoteAnimation(
                RemoteAnimationAdapter(runner, HOME_APPEAR_DURATION, 0),
                RemoteTransition(
                    runner.toRemoteTransition(),
                    iApplicationThread,
                    "StartHomeFromRecents",
                ),
            )
        OverviewComponentObserver.startHomeIntentSafely(this, options.toBundle(), TAG)
        stateManager.moveToRestState()
    }

    private val mAnimationToHomeFactory =
        RemoteAnimationFactory {
            _: Int,
            appTargets: Array<RemoteAnimationTarget>?,
            wallpaperTargets: Array<RemoteAnimationTarget>?,
            nonAppTargets: Array<RemoteAnimationTarget>?,
            result: LauncherAnimationRunner.AnimationResult? ->
            val controller =
                getStateManager().createAnimationToNewWorkspace(BG_LAUNCHER, HOME_APPEAR_DURATION)
            controller.dispatchOnStart()
            val targets =
                RemoteAnimationTargets(
                    appTargets,
                    wallpaperTargets,
                    nonAppTargets,
                    RemoteAnimationTarget.MODE_OPENING,
                )
            for (app in targets.apps) {
                SurfaceControl.Transaction().setAlpha(app.leash, 1f).apply()
            }
            val anim = AnimatorSet()
            anim.play(controller.animationPlayer)
            anim.setDuration(HOME_APPEAR_DURATION)
            result!!.setAnimation(
                anim,
                this@RecentsWindowManager,
                {
                    getStateManager().goToState(BG_LAUNCHER, false)
                    cleanupRecentsWindow()
                },
                true, /* skipFirstFrame */
            )
        }

    private val onBackInvokedCallback: () -> Unit = {
        // If we are in live tile mode, launch the live task, otherwise return home
        recentsView?.runningTaskView?.launchWithAnimation() ?: startHome()
    }

    private fun cleanupRecentsWindow() {
        RecentsWindowProtoLogProxy.logCleanup(isShowing())
        if (isShowing()) {
            windowManager.removeViewImmediate(windowView)
        }
        stateManager.moveToRestState()
        callbacks?.removeListener(recentsAnimationListener)
    }

    private fun isShowing(): Boolean {
        return windowView?.parent != null
    }

    fun startRecentsWindow(callbacks: RecentsAnimationCallbacks? = null) {
        RecentsWindowProtoLogProxy.logStartRecentsWindow(isShowing(), windowView == null)
        if (isShowing()) {
            return
        }
        if (windowView == null) {
            windowView = layoutInflater.inflate(R.layout.fallback_recents_activity, null)
        }
        windowManager.addView(windowView, windowLayoutParams)

        windowView
            ?.findOnBackInvokedDispatcher()
            ?.registerSystemOnBackInvokedCallback(onBackInvokedCallback)

        windowView?.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

        recentsView = windowView?.findViewById(R.id.overview_panel)
        actionsView = windowView?.findViewById(R.id.overview_actions_view)
        scrimView = windowView?.findViewById(R.id.scrim_view)
        memInfoView = windowView?.findViewById(R.id.meminfo)
        val systemUiProxy = SystemUiProxy.INSTANCE[this]
        val splitSelectStateController =
            SplitSelectStateController(
                this,
                getStateManager(),
                null, /* depthController */
                statsLogManager,
                systemUiProxy,
                RecentsModel.INSTANCE[this],
                null, /*activityBackCallback*/
            )
        recentsView?.init(actionsView, splitSelectStateController, null, memInfoView)
        dragLayer = windowView?.findViewById(R.id.drag_layer)

        actionsView?.updateDimension(getDeviceProfile(), recentsView?.lastComputedTaskSize)
        actionsView?.updateVerticalMargin(DisplayController.getNavigationMode(this))

        mSystemUiController = SystemUiController(windowView)
        recentsWindowTracker.handleCreate(this)

        this.callbacks = callbacks
        callbacks?.addListener(recentsAnimationListener)
    }

    private fun recentAnimationStopped() {
        if (isInState(BACKGROUND_APP)) {
            cleanupRecentsWindow()
        }
    }

    override fun getComponentName(): ComponentName {
        return ComponentName(this, RecentsWindowManager::class.java)
    }

    override fun canStartHomeSafely(): Boolean {
        val overviewCommandHelper = tisBindHelper.overviewCommandHelper
        return overviewCommandHelper == null || overviewCommandHelper.canStartHomeSafely()
    }

    override fun getDesktopVisibilityController(): DesktopVisibilityController? {
        return tisBindHelper.desktopVisibilityController
    }

    override fun setTaskbarUIController(taskbarUIController: TaskbarUIController?) {
        this.taskbarUIController = taskbarUIController
    }

    override fun getTaskbarUIController(): TaskbarUIController? {
        return taskbarUIController
    }

    fun registerInitListener(onInitListener: Predicate<Boolean>) {
        this.onInitListener = onInitListener
    }

    override fun collectStateHandlers(out: MutableList<StateManager.StateHandler<RecentsState?>>?) {
        out!!.add(FallbackRecentsStateController(this))
    }

    override fun getStateManager(): StateManager<RecentsState, RecentsWindowManager> {
        return this.stateManager
    }

    override fun shouldAnimateStateChange(): Boolean {
        return true
    }

    override fun isInState(state: RecentsState?): Boolean {
        return stateManager.state == state
    }

    override fun onStateSetStart(state: RecentsState) {
        super.onStateSetStart(state)
        RecentsWindowProtoLogProxy.logOnStateSetStart(getStateName(state))
    }

    override fun onStateSetEnd(state: RecentsState) {
        super.onStateSetEnd(state)
        RecentsWindowProtoLogProxy.logOnStateSetEnd(getStateName(state))

        if (state == HOME || state == BG_LAUNCHER) {
            cleanupRecentsWindow()
        }
        if (state === DEFAULT) {
            AccessibilityManagerCompat.sendStateEventToTest(baseContext, OVERVIEW_STATE_ORDINAL)
        }
    }

    private fun getStateName(state: RecentsState?): String {
        return when (state) {
            null -> "NULL"
            DEFAULT -> "default"
            MODAL_TASK -> "MODAL_TASK"
            BACKGROUND_APP -> "BACKGROUND_APP"
            HOME -> "HOME"
            BG_LAUNCHER -> "BG_LAUNCHER"
            OVERVIEW_SPLIT_SELECT -> "OVERVIEW_SPLIT_SELECT"
            else -> "ordinal=" + state.ordinal
        }
    }

    override fun getSystemUiController(): SystemUiController? {
        if (mSystemUiController == null) {
            mSystemUiController = SystemUiController(rootView)
        }
        return mSystemUiController
    }

    override fun getContext(): Context {
        return this
    }

    override fun getScrimView(): ScrimView? {
        return scrimView
    }

    override fun <T : View?> getOverviewPanel(): T {
        return recentsView as T
    }

    override fun getRootView(): View? {
        return windowView
    }

    override fun getDragLayer(): BaseDragLayer<RecentsWindowManager> {
        return dragLayer!!
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        // TODO(b/368610710)
        return false
    }

    override fun dispatchKeyEvent(ev: KeyEvent?): Boolean {
        // TODO(b/368610710)
        return false
    }

    override fun getActionsView(): OverviewActionsView<*>? {
        return actionsView
    }

    override fun addForceInvisibleFlag(flag: Int) {}

    override fun clearForceInvisibleFlag(flag: Int) {}

    override fun setLocusContext(id: LocusId?, bundle: Bundle?) {
        // no op
    }

    override fun isStarted(): Boolean {
        return isShowing() && isInState(DEFAULT)
    }

    /** Adds a callback for the provided activity event */
    override fun addEventCallback(@BaseActivity.ActivityEvent event: Int, callback: Runnable?) {
        mEventCallbacks[event].add(callback)
    }

    /** Removes a previously added callback */
    override fun removeEventCallback(@BaseActivity.ActivityEvent event: Int, callback: Runnable?) {
        mEventCallbacks[event].remove(callback)
    }

    override fun runOnBindToTouchInteractionService(r: Runnable?) {
        tisBindHelper.runOnBindToTouchInteractionService(r)
    }

    override fun addMultiWindowModeChangedListener(
        listener: BaseActivity.MultiWindowModeChangedListener?
    ) {
        // TODO(b/368408838)
    }

    override fun removeMultiWindowModeChangedListener(
        listener: BaseActivity.MultiWindowModeChangedListener?
    ) {}

    override fun returnToHomescreen() {
        startHome()
    }

    override fun isRecentsViewVisible(): Boolean {
        return isShowing() || getStateManager().state!!.isRecentsViewVisible
    }

    override fun createAtomicAnimationFactory(): AtomicAnimationFactory<RecentsState?>? {
        return RecentsAtomicAnimationFactory<RecentsWindowManager, RecentsState>(this)
    }

    override fun getMemInfoView(): MemInfoView? {
        return memInfoView
    }
}
