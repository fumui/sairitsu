package my.fumui.sairitsu.text

/** One piece of a level's description: a labelled field (`#AUTHOR:#SEPARATOR_COLON:Ryo`) or a paragraph of text. */
sealed interface DescriptionBlock {
    data class Field(val label: String, val value: String) : DescriptionBlock

    data class Paragraph(val text: String) : DescriptionBlock
}

/**
 * Splits a server description into fields and paragraphs, resolving `#KEY` labels and `##LOCALIZE:` values.
 *
 * Search keyword lines are dropped: ones starting `##:`, and a final single-line paragraph that starts with
 * the level's [title] (Sbuga ends descriptions with the title in kana and romaji).
 */
fun parseDescription(raw: String, localizer: Localizer, title: String? = null): List<DescriptionBlock> {
    val blocks = mutableListOf<DescriptionBlock>()
    val paragraph = mutableListOf<String>()
    var lastParagraphLines = 0

    fun flush() {
        if (paragraph.isEmpty()) return
        blocks += DescriptionBlock.Paragraph(paragraph.joinToString("\n"))
        lastParagraphLines = paragraph.size
        paragraph.clear()
    }

    for (line in raw.replace("\r\n", "\n").split('\n')) {
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flush()
            trimmed.startsWith(KEYWORDS_PREFIX) -> flush()
            else -> {
                val field = parseField(trimmed, localizer)
                if (field != null) {
                    flush()
                    blocks += field
                } else {
                    paragraph += localizer.text(trimmed)
                }
            }
        }
    }
    flush()

    val last = blocks.lastOrNull()
    if (title != null && title.isNotBlank() && last is DescriptionBlock.Paragraph && lastParagraphLines == 1 &&
        last.text.startsWith("$title ")
    ) {
        blocks.removeAt(blocks.lastIndex)
    }
    return blocks
}

/** `#A:#SEPARATOR_COLON:value` → Field("A", "value"); null when the line doesn't start with a `#KEY` label. */
private fun parseField(line: String, localizer: Localizer): DescriptionBlock.Field? {
    val parts = line.split(':')
    var keys = 0
    while (keys < parts.size && KEY.matches(parts[keys])) keys++
    if (keys == 0 || keys == parts.size) return null
    val labelKeys = parts.take(keys).filterNot { it.startsWith(SEPARATOR) }
    if (labelKeys.isEmpty()) return null
    val label = localizer.text(labelKeys.joinToString(":"))
    val value = localizer.text(parts.drop(keys).joinToString(":").trim())
    return DescriptionBlock.Field(label, value)
}

private const val KEYWORDS_PREFIX = "##:"
private const val SEPARATOR = "#SEPARATOR_"
private val KEY = Regex("#[A-Z0-9_]+")
