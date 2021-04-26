package com.airbnb.epoxy

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.core.app.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent

/**
 * A helper to allow binding EpoxyModels to views outside of a RecyclerView. It is recommended to
 * use the extension functions [epoxyView] and [optionalEpoxyView] on Activity/Fragment/View Groups.
 *
 * This is helpful in two cases:
 * 1. You want to dynamically insert a view (of unknown or dynamic type) into a layout
 * 2. You have a predefined view in a layout and you want to update its data functionally with an
 * EpoxyModel
 */
class EpoxyViewBinder : EpoxyController() {

    private var tempModel: EpoxyModel<*>? = null

    private var interceptor: Interceptor? = null

    override fun addInterceptor(interceptor: Interceptor) {
        this.interceptor = interceptor
    }

    internal override fun addInternal(modelToAdd: EpoxyModel<*>) {
        require(tempModel == null) {
            "A model was already added to the EpoxyController. Only one should be added."
        }
        interceptor?.intercept(listOf(modelToAdd))
        // Don't call super to avoid epoxy complaining that we are not in a buildModels call
        tempModel = modelToAdd
    }

    // This needs to be hardcoded to prevent super from crashing, since we aren't actually in model
    // building mode
    internal override fun isModelAddedMultipleTimes(model: EpoxyModel<*>?) = false

    internal override fun addAfterInterceptorCallback(callback: ModelInterceptorCallback) {
        // No-op
    }

    override fun buildModels() {
        // Not used
    }

    /**
     * Bind the model to the view.
     * - If the model is null, the view will be hidden.
     * - If the view has previously been bound to another model, a partial bind will be done to only
     * update the properties that have changed.
     */
    fun <T : View> bind(view: T, model: EpoxyModel<T>?) {
        val newModel = model ?: run {
            // No model added, so the view is hidden
            view.isVisible = false
            return
        }
        view.isVisible = true

        val existingHolder = view.viewHolder

        val viewHolder =
            if (existingHolder == null || !newModel.hasSameViewType(existingHolder.model)) {
                EpoxyViewHolder(view.parent, view, false)
            } else {
                existingHolder
            }

        bind(viewHolder, newModel, existingHolder?.model)
    }

    /**
     * Replaces an existing view if it exists, else creates a new view. This is similar to
     * [replaceView] but it does not add the view to the parent [ViewGroup]. This allows for custom
     * layout handling (e.g. adding constraints for a ConstraintLayout).
     *
     * If the previous view is the same view type then it is reused. The new view is set to have the
     * same ID as the old view if it exists. If not then a new ID is generated.
     *
     * @return the [View] the model has been bound to.
     */
    fun replaceOrCreateView(
        parentView: ViewGroup,
        previousView: View?,
        model: EpoxyModel<*>
    ): View {
        val existingHolder = previousView?.viewHolder

        val viewHolder =
            if (existingHolder == null || !model.hasSameViewType(existingHolder.model)) {
                val newView = model.buildView(parentView)
                newView.id = previousView?.id ?: ViewCompat.generateViewId()
                parentView.addView(newView)

                EpoxyViewHolder(parentView, newView, false)
            } else {
                existingHolder
            }

        bind(viewHolder, model, null)
        return viewHolder.itemView
    }

    /**
     * Similar to [replaceView] but the model is provided via a lambda that adds it to an
     * [EpoxyController].
     */
    fun replaceView(previousView: View, modelProvider: EpoxyController.() -> Unit): View {
        @Suppress("UNUSED_EXPRESSION")
        modelProvider()
        val model = tempModel
        tempModel = null

        return replaceView(previousView, model)
    }

    /**
     * Creates a view for the model and swaps it in place in the parent of the previous view.
     * - If the previous view is the same view type, it is reused.
     * - If the model is null, the view is hidden.
     * - The new view is set to have the same ID as the old view.
     */
    fun replaceView(previousView: View, model: EpoxyModel<*>?): View {
        val newModel = model ?: run {
            // No model added, so the view is hidden
            previousView.isVisible = false
            return previousView
        }

        val existingHolder = previousView.viewHolder

        val viewHolder =
            if (existingHolder == null || !newModel.hasSameViewType(existingHolder.model)) {
                val parent = previousView.parent as ViewGroup
                val newView = newModel.buildView(parent)
                newView.id = previousView.id

                val index = parent.indexOfChild(previousView)
                parent.removeViewInLayout(previousView)
                parent.addView(newView, index, previousView.layoutParams)

                EpoxyViewHolder(parent, newView, false)
            } else {
                existingHolder
            }

        val newView = viewHolder.itemView.apply {
            isVisible = true
            id = previousView.id
        }

        bind(viewHolder, newModel, existingHolder?.model)
        return newView
    }

    /**
     * Takes an [EpoxyModel] added by the lambda, and creates a view for it in the given container.
     * The container should be empty, and the view will be added to it and bound to the model. If
     * the container was previously bound to another model, the existing view will be reused and
     * updated if necessary.
     *
     * @param modelProvider this lambda should be used to add a model to the [EpoxyController]
     * receiver. If no model is added the container will be cleared.
     */
    fun insertInto(container: ViewGroup, modelProvider: EpoxyController.() -> Unit) {
        require(container.childCount <= 1) { "Container cannot have more than one child" }

        // This lambda should add a model to the EpoxyController, which will end up calling
        // "addInternal" and set tempModel
        modelProvider()

        val newModel = tempModel ?: run {
            container.removeAllViews()
            return
        }

        val existingView: View? = container.getChildAt(0)
        val existingHolder = existingView?.viewHolder

        val viewHolder =
            if (existingHolder == null || !newModel.hasSameViewType(existingHolder.model)) {
                container.removeAllViews()
                val view = newModel.buildView(container)
                container.addView(view)
                EpoxyViewHolder(container, view, false)
            } else {
                existingHolder
            }

        bind(viewHolder, newModel, existingHolder?.model)
        tempModel = null
    }

    /**
     * Unbinds any model that is currently bound to this view. If no model is bound this is a no-op.
     */
    fun unbind(view: View) {
        val viewHolder = view.viewHolder ?: return
        viewHolder.unbind()
        view.viewHolder = null
    }

    private fun bind(
        viewHolder: EpoxyViewHolder,
        newModel: EpoxyModel<*>,
        existingModel: EpoxyModel<*>?
    ) {
        if (existingModel != newModel) {
            viewHolder.bind(newModel, existingModel, emptyList(), 0)
            viewHolder.itemView.viewHolder = viewHolder
        }
    }

    private fun EpoxyModel<*>.hasSameViewType(model: EpoxyModel<*>): Boolean =
        ViewTypeManager.getViewType(this) == ViewTypeManager.getViewType(model)
}

/**
 * Shortcut for creating a [LifecycleAwareEpoxyViewBinder] in a lazy way. 
 * See [LifecycleAwareEpoxyViewBinder] for pass-through parameter documentation.
 *
 * @param initializer a lambda that is run directly after instantiation of the
 * [LifecycleAwareEpoxyViewBinder].
 */
fun ComponentActivity.epoxyView(
    @IdRes viewId: Int,
    useVisibilityTracking: Boolean = false,
    initializer: LifecycleAwareEpoxyViewBinder.() -> Unit,
    modelProvider: EpoxyController.() -> Unit
) = lazy {
    return@lazy epoxyViewInternal(viewId, useVisibilityTracking, initializer, modelProvider)
}

/**
 * Shortcut for creating a [LifecycleAwareEpoxyViewBinder] in a lazy way.
 * See [LifecycleAwareEpoxyViewBinder] for pass-through parameter documentation.
 *
 * @param initializer a lambda that is run directly after instantiation of the
 * [LifecycleAwareEpoxyViewBinder].
 */
fun Fragment.epoxyView(
    @IdRes viewId: Int,
    useVisibilityTracking: Boolean = false,
    initializer: LifecycleAwareEpoxyViewBinder.() -> Unit,
    modelProvider: EpoxyController.() -> Unit
) = lazy {
    return@lazy epoxyViewInternal(viewId, useVisibilityTracking, initializer, modelProvider)
}

/**
 * Shortcut for creating a [LifecycleAwareEpoxyViewBinder] in a lazy way.
 * See [LifecycleAwareEpoxyViewBinder] for pass-through parameter documentation.
 *
 * @param initializer a lambda that is run directly after instantiation of the
 * [LifecycleAwareEpoxyViewBinder].
 */
fun ViewGroup.epoxyView(
    @IdRes viewId: Int,
    useVisibilityTracking: Boolean = false,
    initializer: LifecycleAwareEpoxyViewBinder.() -> Unit,
    modelProvider: EpoxyController.() -> Unit
) = lazy {
    return@lazy epoxyViewInternal(viewId, useVisibilityTracking, initializer, modelProvider)
}

/**
 * Shortcut for creating a [LifecycleAwareEpoxyViewBinder] in a lazy way.
 * See [LifecycleAwareEpoxyViewBinder] for pass-through parameter documentation.
 *
 * @param initializer a lambda that is run directly after instantiation of the
 * [LifecycleAwareEpoxyViewBinder].
 *
 * @return a view binder or null if a view with the [viewId] could not be found.
 */
fun ComponentActivity.optionalEpoxyView(
    @IdRes viewId: Int,
    useVisibilityTracking: Boolean = false,
    fallbackToNameLookup: Boolean = false,
    initializer: LifecycleAwareEpoxyViewBinder.() -> Unit = { },
    modelProvider: EpoxyController.() -> Unit
) = lazy {
    val view = findViewById<View>(android.R.id.content)
    // View id is not present, we just return null in that case.
    if (view.maybeFindViewByIdName<View>(viewId, fallbackToNameLookup) == null) return@lazy null

    return@lazy epoxyViewInternal(viewId, useVisibilityTracking, initializer, modelProvider)
}

/**
 * Shortcut for creating a [LifecycleAwareEpoxyViewBinder] in a lazy way.
 * See [LifecycleAwareEpoxyViewBinder] for pass-through parameter documentation.
 *
 * @param initializer a lambda that is run directly after instantiation of the
 * [LifecycleAwareEpoxyViewBinder].
 *
 * @return a view binder or null if a view with the [viewId] could not be found.
 */
fun Fragment.optionalEpoxyView(
    @IdRes viewId: Int,
    useVisibilityTracking: Boolean = false,
    fallbackToNameLookup: Boolean = false,
    initializer: (LifecycleAwareEpoxyViewBinder.() -> Unit) = { },
    modelProvider: EpoxyController.() -> Unit
) = lazy {
    val view = view ?: error("Fragment view has not been created")
    // View id is not present, we just return null in that case.
    if (view.maybeFindViewByIdName<View>(viewId, fallbackToNameLookup) == null) return@lazy null

    return@lazy epoxyViewInternal(viewId, useVisibilityTracking, initializer, modelProvider)
}

/**
 * Shortcut for creating a [LifecycleAwareEpoxyViewBinder] in a lazy way.
 * See [LifecycleAwareEpoxyViewBinder] for pass-through parameter documentation.
 *
 * @param initializer a lambda that is run directly after instantiation of the
 * [LifecycleAwareEpoxyViewBinder].
 * 
 * @return a view binder or null if a view with the [viewId] could not be found.
 */
fun ViewGroup.optionalEpoxyView(
    @IdRes viewId: Int,
    useVisibilityTracking: Boolean = false,
    fallbackToNameLookup: Boolean = false,
    initializer: LifecycleAwareEpoxyViewBinder.() -> Unit = { },
    modelProvider: EpoxyController.() -> Unit
) = lazy {
    val view = this
    // View id is not present, we just return null in that case.
    if (view.maybeFindViewByIdName<View>(viewId, fallbackToNameLookup) == null) return@lazy null

    return@lazy epoxyViewInternal(viewId, useVisibilityTracking, initializer, modelProvider)
}

private fun ComponentActivity.epoxyViewInternal(
    @IdRes viewId: Int,
    useVisibilityTracking: Boolean = false,
    initializer: LifecycleAwareEpoxyViewBinder.() -> Unit,
    modelProvider: EpoxyController.() -> Unit
) = LifecycleAwareEpoxyViewBinder(
    this,
    { findViewById(android.R.id.content) },
    viewId,
    useVisibilityTracking = useVisibilityTracking,
    modelProvider = modelProvider
).apply(initializer)

private fun Fragment.epoxyViewInternal(
    @IdRes viewId: Int,
    useVisibilityTracking: Boolean = false,
    initializer: LifecycleAwareEpoxyViewBinder.() -> Unit,
    modelProvider: EpoxyController.() -> Unit
) = LifecycleAwareEpoxyViewBinder(
    viewLifecycleOwner,
    { view },
    viewId,
    useVisibilityTracking = useVisibilityTracking,
    modelProvider = modelProvider
).apply(initializer)

private fun ViewGroup.epoxyViewInternal(
    @IdRes viewId: Int,
    useVisibilityTracking: Boolean = false,
    initializer: LifecycleAwareEpoxyViewBinder.() -> Unit,
    modelProvider: EpoxyController.() -> Unit
) = LifecycleAwareEpoxyViewBinder(
    (this.context as? LifecycleOwner) ?: error("LifecycleOwner required as view's context "),
    { this },
    viewId,
    useVisibilityTracking = useVisibilityTracking,
    modelProvider = modelProvider
).apply(initializer)

/**
 * This class uses an epoxy model to update a view. The view reference is cleared when the fragment
 * is stopped. Call [invalidate] to have the model rebuilt and rebound to the view.
 *
 * @param rootView a lambda returning the parent [ViewGroup] that will be used to search for the
 * [viewId].
 * @param viewId the ID of the view stub that the Epoxy view should replace. This tells the
 * view binder where in the layout to put the view. This should correspond to a view of type
 * [EpoxyViewStub] for state restoration reasons.
 * @param useVisibilityTracking true to get visibility callbacks using a partial impression
 * percentage threshold of 100%, false to not track view visibility. See
 * [EpoxyViewBinderVisibilityTracker] for more information.
 * @param fallbackToNameLookup true to also include searching by the entry name 
 * ([Resources.getResourceEntryName]) should the [viewId] not be found. Useful for dynamic features
 * as it's possible the generated ID is not the same should the same view ID exist in both the base
 * APK and the dynamic feature.
 * @param modelProvider a lambda for building the [EpoxyModel]. It expects a single model to be
 * added to the controller receiver. If no model is added the view will be hidden.
 */
class LifecycleAwareEpoxyViewBinder(
    private val lifecycleOwner: LifecycleOwner,
    private val rootView: (() -> View?),
    @IdRes private val viewId: Int,
    private val useVisibilityTracking: Boolean = false,
    private val fallbackToNameLookup: Boolean = false,
    private val modelProvider: EpoxyController.() -> Unit,
) : LifecycleObserver {
    private val viewBinder = EpoxyViewBinder()
    private var lazyView: View? = null

    private val visibilityTracker: EpoxyViewBinderVisibilityTracker by lazy {
        EpoxyViewBinderVisibilityTracker().apply {
            this.partialImpressionThresholdPercentage = 100
        }
    }

    val view: View
        get() {
            if (lazyView == null) {
                val nonNullRootView = rootView() ?: error("Fragment view is not created")
                lazyView = nonNullRootView.maybeFindViewByIdName(viewId, fallbackToNameLookup)
                    ?: error(
                        "View could not be found, fallbackToNameLookup: $fallbackToNameLookup," +
                            " view id name: ${nonNullRootView.resources.getResourceEntryName(viewId)}"
                    )
                // Propagate an error if a non EpoxyViewStub is used
                if (lazyView !is EpoxyViewStub) {
                    val resourceNameWithFallback = try {
                        nonNullRootView.resources.getResourceName(viewId)
                    } catch (e: Resources.NotFoundException) {
                        "$viewId (name not found)"
                    }
                    viewBinder.onExceptionSwallowed(
                        IllegalStateException(
                            "View binder should be using EpoxyViewStub. View ID: $resourceNameWithFallback"
                        )
                    )
                }

                // Register this for view lifecycle callbacks so that it can clear the view when it
                // is destroyed. This both prevents a memory leak, and ensures that if the view is
                // recreated it can look up the reference again. This MUST register the observer
                // again each time the view is created because the fragment's viewLifecycleOwner
                // is updated to a new instance for each new fragment view.
                lifecycleOwner.lifecycle.addObserver(this)
            }

            return lazyView!!
        }

    /** Adds an [EpoxyController.Interceptor] to the [EpoxyViewBinder]. **/
    fun addInterceptor(interceptor: EpoxyController.Interceptor) {
        viewBinder.addInterceptor(interceptor)
    }

    /** Replaces the [View] with the model produced by the [modelProvider] lambda. */
    fun invalidate() {
        lazyView = viewBinder.replaceView(view, modelProvider).also {
            if (useVisibilityTracking) {
                visibilityTracker.attach(it)
            }
        }
    }

    /**
     * Unbinds the [EpoxyViewBinder]. It is normally not necessary to call this as this object
     * registers itself with the provided [lifecycleOwner].
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onViewDestroyed() {
        lazyView?.let { viewBinder.unbind(it) }
        lazyView = null
        if (useVisibilityTracking) {
            visibilityTracker.detach()
        }
    }
}