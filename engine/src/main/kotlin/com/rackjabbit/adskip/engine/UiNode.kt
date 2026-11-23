package com.rackjabbit.adskip.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class UiNode(
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val bounds: Bounds = Bounds.EMPTY,
    val children: List<UiNode> = emptyList(),
    @Transient val path: List<Int> = emptyList(),
) {
    fun withPaths(path: List<Int> = emptyList()): UiNode = copy(
        path = path,
        children = children.mapIndexed { index, child -> child.withPaths(path + index) },
    )

    fun flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }

    /** On screen for the person holding the phone: the platform says it is visible and it has area. */
    fun shownToUser(): Boolean = visible && !bounds.isEmpty
}

fun pathDistance(a: List<Int>, b: List<Int>): Int {
    val common = a.zip(b).takeWhile { (left, right) -> left == right }.count()
    return (a.size - common) + (b.size - common)
}

fun viewIdEntry(viewId: String): String = viewId.substringAfterLast('/').substringAfterLast(':')
