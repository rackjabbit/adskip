package com.rackjabbit.adskip.engine

object NodeTreeFormatter {
    fun format(
        root: UiNode,
        packageName: String,
        capturedAt: String,
        detection: Detection?,
        maxNodes: Int = 2000,
        maxDepth: Int = 30,
        notes: List<String> = emptyList(),
    ): String {
        val tree = root.withPaths()
        val builder = StringBuilder()
        builder.appendLine("Ad Skip node tree")
        builder.appendLine("package: $packageName")
        builder.appendLine("captured: $capturedAt")
        if (detection == null) {
            builder.appendLine("adPlaying: unknown")
        } else {
            builder.appendLine("adPlaying: ${detection.adPlaying}")
            builder.appendLine("label: ${detection.label.orEmpty()}")
            builder.appendLine("countdownSeconds: ${detection.countdownSeconds?.toString().orEmpty()}")
            val skip = detection.skip
            if (skip == null) {
                builder.appendLine("skip: none")
            } else {
                builder.appendLine(
                    "skip: label=${sanitize(skip.label)} ready=${skip.ready} " +
                        "path=${skip.nodePath.joinToString("/")} " +
                        "clickPath=${skip.clickablePath?.joinToString("/") ?: "none"}",
                )
            }
            if (detection.hits.isEmpty()) {
                builder.appendLine("matches: none")
            } else {
                builder.appendLine("matches:")
                detection.hits.forEach { hit ->
                    builder.appendLine(
                        "- ${hit.role} strength=${hit.strength} source=${hit.source} match=${hit.match} " +
                            "value=${sanitize(hit.value)} path=${hit.path.joinToString("/")} " +
                            "id=${hit.viewId.orEmpty()} text=${sanitize(hit.text.orEmpty())} " +
                            "desc=${sanitize(hit.contentDescription.orEmpty())} visible=${hit.visible} " +
                            "bounds=[${hit.bounds.left},${hit.bounds.top},${hit.bounds.right},${hit.bounds.bottom}]",
                    )
                }
            }
        }
        notes.forEach { line -> builder.appendLine(line) }
        builder.appendLine()
        var written = 0
        var truncated = false
        fun walk(node: UiNode, depth: Int) {
            if (written >= maxNodes || depth > maxDepth) {
                truncated = true
                return
            }
            written += 1
            val indent = "  ".repeat(depth)
            val id = node.viewId?.let { " id=$it" }.orEmpty()
            val className = node.className ?: "node"
            builder.appendLine(
                "$indent${node.path.joinToString(".")} $className$id " +
                    "clickable=${node.clickable} enabled=${node.enabled} visible=${node.visible} " +
                    "bounds=[${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}]",
            )
            node.text?.let { builder.appendLine("$indent  text=${sanitize(it)}") }
            node.contentDescription?.let { builder.appendLine("$indent  desc=${sanitize(it)}") }
            node.children.forEach { child -> walk(child, depth + 1) }
        }
        walk(tree, 0)
        builder.appendLine()
        builder.appendLine("nodes: $written")
        builder.appendLine("truncated: $truncated")
        return builder.toString()
    }

    private fun sanitize(value: String): String = value.replace("\n", "\\n").replace("\r", "").take(500)
}
