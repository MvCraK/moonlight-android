package com.limelight.ss

import android.app.Activity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.appcompat.widget.TooltipCompat
import com.limelight.R
import com.limelight.ui.ExpressiveToolbarButton
import java.util.WeakHashMap

/**
 * Inserts the SS button right after PcView's EasyTier button at runtime:
 * beside it when the toolbar is a row (portrait), below it when it is a column
 * (landscape), re-anchoring whatever sat below EasyTier. Re-applied whenever
 * PcView re-inflates its layout.
 */
object SsToolbar {
    private class Binding(val activity: Activity) {
        val statusListener: () -> Unit = { update(activity) }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { ensureButton(activity) }
    }

    private val bindings = WeakHashMap<Activity, Binding>()

    fun attach(activity: Activity) {
        if (bindings.containsKey(activity)) return
        val binding = Binding(activity)
        bindings[activity] = binding
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(binding.layoutListener)
        ensureButton(activity)
    }

    fun resume(activity: Activity) {
        val binding = bindings[activity] ?: return
        SsRoutes.addListener(binding.statusListener)
        update(activity)
        SsRoutes.probeAsync()
    }

    fun pause(activity: Activity) {
        bindings[activity]?.let { SsRoutes.removeListener(it.statusListener) }
    }

    fun detach(activity: Activity) {
        val binding = bindings.remove(activity) ?: return
        SsRoutes.removeListener(binding.statusListener)
        activity.window.decorView.viewTreeObserver.let {
            if (it.isAlive) it.removeOnGlobalLayoutListener(binding.layoutListener)
        }
        SsPanel.release(activity)
    }

    private fun ensureButton(activity: Activity) {
        if (activity.findViewById<View?>(R.id.ssControlButton) != null) return
        val anchor = activity.findViewById<View?>(R.id.easyTierControlButton) ?: return
        val parent = anchor.parent as? RelativeLayout ?: return
        val anchorParams = anchor.layoutParams as? RelativeLayout.LayoutParams ?: return

        val button = ExpressiveToolbarButton(activity, null, android.R.attr.borderlessButtonStyle).apply {
            id = R.id.ssControlButton
            scaleType = ImageView.ScaleType.FIT_CENTER
            cropToPadding = false
            setPadding(anchor.paddingLeft, anchor.paddingTop, anchor.paddingRight, anchor.paddingBottom)
            setImageDrawable(SsStatusDrawable(activity))
            setOnClickListener { SsPanel.of(activity).show() }
        }
        val params = RelativeLayout.LayoutParams(anchorParams.width, anchorParams.height)
        val rules = anchorParams.rules
        val inRow = rules[RelativeLayout.END_OF] != 0 || rules[RelativeLayout.RIGHT_OF] != 0
        if (inRow) {
            if (rules[RelativeLayout.BELOW] != 0) params.addRule(RelativeLayout.BELOW, rules[RelativeLayout.BELOW])
            params.addRule(RelativeLayout.END_OF, anchor.id)
            params.addRule(RelativeLayout.RIGHT_OF, anchor.id)
        } else {
            params.addRule(RelativeLayout.BELOW, anchor.id)
            for (rule in intArrayOf(RelativeLayout.ALIGN_PARENT_START, RelativeLayout.ALIGN_PARENT_LEFT)) {
                if (rules[rule] != 0) params.addRule(rule)
            }
            // Keep the column contiguous: views stacked under EasyTier move under SS.
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val lp = child.layoutParams as? RelativeLayout.LayoutParams ?: continue
                if (child !== anchor && lp.rules[RelativeLayout.BELOW] == anchor.id) {
                    lp.addRule(RelativeLayout.BELOW, R.id.ssControlButton)
                    child.layoutParams = lp
                }
            }
        }
        button.nextFocusForwardId = anchor.nextFocusForwardId
        anchor.nextFocusForwardId = R.id.ssControlButton
        parent.addView(button, parent.indexOfChild(anchor) + 1, params)
        update(activity)
    }

    /** Grey = off, green = connected, orange = a node is unreachable. */
    private fun update(activity: Activity) {
        val button = activity.findViewById<ImageButton?>(R.id.ssControlButton) ?: return
        val drawable = button.drawable as? SsStatusDrawable ?: return
        val status = SsRoutes.status()
        drawable.status = status
        val label = activity.getString(when (status) {
            SsRoutes.Status.OFF -> R.string.ss_state_off
            SsRoutes.Status.CHECKING -> R.string.ss_state_checking
            SsRoutes.Status.ONLINE -> R.string.ss_state_online
            SsRoutes.Status.DEGRADED -> R.string.ss_state_degraded
        })
        button.contentDescription = activity.getString(R.string.ss_toolbar_state, label)
        TooltipCompat.setTooltipText(button, button.contentDescription)
    }
}
