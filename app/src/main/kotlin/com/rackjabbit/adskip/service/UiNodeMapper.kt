package com.rackjabbit.adskip.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.rackjabbit.adskip.engine.Bounds
import com.rackjabbit.adskip.engine.UiNode

object UiNodeMapper {
    fun map(root: AccessibilityNodeInfo, maxNodes: Int = 1800, maxDepth: Int = 35): UiNode {
        val count = intArrayOf(0)
        return mapNode(root, emptyList(), count, maxNodes, maxDepth, 0)
    }

    private fun mapNode(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        count: IntArray,
        maxNodes: Int,
        maxDepth: Int,
        depth: Int,
    ): UiNode {
        count[0] += 1
        val children = ArrayList<UiNode>()
        if (count[0] < maxNodes && depth < maxDepth) {
            val childCount = try {
                node.childCount
            } catch (_: Exception) {
                0
            }
            for (index in 0 until childCount) {
                if (count[0] >= maxNodes) break
                val child = try {
                    node.getChild(index)
                } catch (_: Exception) {
                    null
                } ?: continue
                try {
                    children += mapNode(child, path + index, count, maxNodes, maxDepth, depth + 1)
                } finally {
                    @Suppress("DEPRECATION")
                    try {
                        child.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (_: Exception) {
        }
        return UiNode(
            viewId = try {
                node.viewIdResourceName
            } catch (_: Exception) {
                null
            },
            text = try {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            },
            contentDescription = try {
                node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            },
            className = try {
                node.className?.toString()
            } catch (_: Exception) {
                null
            },
            clickable = try {
                node.isClickable
            } catch (_: Exception) {
                false
            },
            enabled = try {
                node.isEnabled
            } catch (_: Exception) {
                true
            },
            visible = try {
                node.isVisibleToUser
            } catch (_: Exception) {
                true
            },
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
            path = path,
        )
    }
}
