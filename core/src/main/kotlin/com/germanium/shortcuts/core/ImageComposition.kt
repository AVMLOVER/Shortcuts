package com.germanium.shortcuts.core

data class ImageSize(val width: Int, val height: Int)
data class ImagePlacement(val x: Int, val y: Int, val width: Int, val height: Int)
data class ImageComposition(val width: Int, val height: Int, val placements: List<ImagePlacement>) {
    companion object {
        fun plan(sizes: List<ImageSize>, direction: String, spacing: Int, columns: Int, equal: Boolean): ImageComposition {
            require(sizes.isNotEmpty() && sizes.size <= 16 && sizes.all { it.width in 1..16384 && it.height in 1..16384 } && spacing in 0..1024 && columns in 1..16) { throw Problem("err_image") }
            fun fit(size: ImageSize, width: Int? = null, height: Int? = null): ImageSize = when {
                width != null -> ImageSize(width, (size.height.toLong() * width / size.width).toInt().coerceAtLeast(1))
                height != null -> ImageSize((size.width.toLong() * height / size.height).toInt().coerceAtLeast(1), height)
                else -> size
            }
            val placements = mutableListOf<ImagePlacement>(); var width: Int; var height: Int
            when (direction) {
                "horizontal" -> {
                    val normalized = sizes.map { if (equal) fit(it, height = sizes.first().height) else it }
                    width = normalized.sumOf { it.width.toLong() }.plus(spacing.toLong() * (sizes.size - 1)).also { require(it <= 16384) { throw Problem("err_image") } }.toInt(); height = normalized.maxOf { it.height }
                    var x = 0; normalized.forEach { placements += ImagePlacement(x, (height - it.height) / 2, it.width, it.height); x += it.width + spacing }
                }
                "vertical" -> {
                    val normalized = sizes.map { if (equal) fit(it, width = sizes.first().width) else it }
                    width = normalized.maxOf { it.width }; height = normalized.sumOf { it.height.toLong() }.plus(spacing.toLong() * (sizes.size - 1)).also { require(it <= 16384) { throw Problem("err_image") } }.toInt()
                    var y = 0; normalized.forEach { placements += ImagePlacement((width - it.width) / 2, y, it.width, it.height); y += it.height + spacing }
                }
                "grid" -> {
                    val cols = columns.coerceAtMost(sizes.size); val rows = (sizes.size + cols - 1) / cols
                    val cellWidth = sizes.maxOf { it.width }; val cellHeight = sizes.maxOf { it.height }
                    width = (cellWidth.toLong() * cols + spacing.toLong() * (cols - 1)).also { require(it <= 16384) { throw Problem("err_image") } }.toInt()
                    height = (cellHeight.toLong() * rows + spacing.toLong() * (rows - 1)).also { require(it <= 16384) { throw Problem("err_image") } }.toInt()
                    sizes.forEachIndexed { index, size ->
                        val scale = minOf(cellWidth.toDouble() / size.width, cellHeight.toDouble() / size.height, if (equal) Double.MAX_VALUE else 1.0)
                        val w = (size.width * scale).toInt().coerceAtLeast(1); val h = (size.height * scale).toInt().coerceAtLeast(1)
                        placements += ImagePlacement(index % cols * (cellWidth + spacing) + (cellWidth - w) / 2, index / cols * (cellHeight + spacing) + (cellHeight - h) / 2, w, h)
                    }
                }
                else -> throw Problem("err_image")
            }
            require(width in 1..16384 && height in 1..16384 && width.toLong() * height <= 16_000_000) { throw Problem("err_image") }
            return ImageComposition(width, height, placements)
        }
    }
}
