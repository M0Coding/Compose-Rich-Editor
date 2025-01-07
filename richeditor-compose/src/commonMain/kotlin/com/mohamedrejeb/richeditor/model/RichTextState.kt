package com.mohamedrejeb.richeditor.model

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.paragraph.RichParagraph
import com.mohamedrejeb.richeditor.paragraph.type.*
import com.mohamedrejeb.richeditor.paragraph.type.ParagraphType.Companion.startText
import com.mohamedrejeb.richeditor.parser.html.RichTextStateHtmlParser
import com.mohamedrejeb.richeditor.parser.markdown.RichTextStateMarkdownParser
import com.mohamedrejeb.richeditor.utils.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.reflect.KClass

@Composable
public fun rememberRichTextState(): RichTextState {
    return rememberSaveable(saver = RichTextState.Saver) {
        RichTextState()
    }
}

@OptIn(ExperimentalRichTextApi::class)
public class RichTextState internal constructor(
    initialRichParagraphList: List<RichParagraph>,
) {
    public constructor() : this(listOf(RichParagraph()))

    internal val richParagraphList = mutableStateListOf<RichParagraph>()
    internal var visualTransformation: VisualTransformation by mutableStateOf(VisualTransformation.None)
    internal var textFieldValue by mutableStateOf(TextFieldValue())
        private set

    internal val inlineContentMap = mutableStateMapOf<String, InlineTextContent>()
    internal val usedInlineContentMapKeys = mutableSetOf<String>()

    /**
     * The annotated string representing the rich text.
     */
    public var annotatedString: AnnotatedString by mutableStateOf(AnnotatedString(text = ""))
        private set

    /**
     * The selection of the rich text.
     */
    public var selection: TextRange
        get() = textFieldValue.selection
        set(value) {
            if (value.min >= 0 && value.max <= textFieldValue.text.length) {
                val newTextFieldValue = textFieldValue.copy(selection = value)
                updateTextFieldValue(newTextFieldValue)
            }
        }

    public val composition: TextRange? get() = textFieldValue.composition

    internal var singleParagraphMode by mutableStateOf(false)

    internal var textLayoutResult: TextLayoutResult? by mutableStateOf(null)
        private set

    private var lastPressPosition: Offset? by mutableStateOf(null)

    private var currentAppliedSpanStyle: SpanStyle by mutableStateOf(
        getRichSpanByTextIndex(textIndex = selection.min - 1)?.fullSpanStyle
            ?: RichSpanStyle.DefaultSpanStyle
    )

    private var currentAppliedRichSpanStyle: RichSpanStyle by mutableStateOf(
        getRichSpanByTextIndex(textIndex = selection.min - 1)?.richSpanStyle
            ?: RichSpanStyle.Default
    )

    /**
     * Returns whether the current selected text is a link.
     */
    public val isLink: Boolean get() = currentAppliedRichSpanStyle::class == RichSpanStyle.Link::class

    /**
     * Returns the selected link text.
     */
    public val selectedLinkText: String?
        get() =
            if (isLink)
                getRichSpanByTextIndex(textIndex = selection.min - 1)?.text
            else
                null

    /**
     * Returns the selected link URL.
     */
    public val selectedLinkUrl: String? get() = (currentAppliedRichSpanStyle as? RichSpanStyle.Link)?.url

    @Deprecated(
        message = "Use isCodeSpan instead",
        replaceWith = ReplaceWith("isCodeSpan"),
        level = DeprecationLevel.ERROR,
    )
    public val isCode: Boolean get() = isCodeSpan

    /**
     * Returns whether the current selected text is a code span.
     */
    public val isCodeSpan: Boolean get() = currentRichSpanStyle::class == RichSpanStyle.Code::class

    private var toAddSpanStyle: SpanStyle by mutableStateOf(SpanStyle())
    private var toRemoveSpanStyle: SpanStyle by mutableStateOf(SpanStyle())

    private var toAddRichSpanStyle: RichSpanStyle by mutableStateOf(RichSpanStyle.Default)
    private var toRemoveRichSpanStyleKClass: KClass<out RichSpanStyle> by mutableStateOf(RichSpanStyle.Default::class)

    @Deprecated(
        message = "Use isRichSpan with T or KClass instead",
        replaceWith = ReplaceWith("isRichSpan<>()"),
        level = DeprecationLevel.WARNING,
    )
    public fun isRichSpan(spanStyle: RichSpanStyle): Boolean =
        isRichSpan(spanStyle::class)

    public inline fun <reified T : RichSpanStyle> isRichSpan(): Boolean =
        isRichSpan(T::class)

    public fun isRichSpan(kClass: KClass<out RichSpanStyle>): Boolean {
        return currentRichSpanStyle::class == kClass
    }

    /**
     * The current span style.
     * If the selection is collapsed, the span style is the style of the character preceding the selection.
     * If the selection is not collapsed, the span style is the style of the selection.
     */
    public val currentSpanStyle: SpanStyle
        get() = currentAppliedSpanStyle.customMerge(toAddSpanStyle).unmerge(toRemoveSpanStyle)

    /**
     * The current rich span style.
     * If the selection is collapsed, the rich span style is the style of the character preceding the selection.
     * If the selection is not collapsed, the rich span style is the style of the selection.
     */
    public val currentRichSpanStyle: RichSpanStyle
        get() = when {
            currentAppliedRichSpanStyle::class == RichSpanStyle.Default::class ->
                toAddRichSpanStyle

            currentAppliedRichSpanStyle::class == toRemoveRichSpanStyleKClass ->
                RichSpanStyle.Default

            else ->
                currentAppliedRichSpanStyle
        }

    internal var styledRichSpanList = mutableStateListOf<RichSpan>()
        private set

    private var currentAppliedParagraphStyle: ParagraphStyle by mutableStateOf(
        getRichParagraphByTextIndex(textIndex = selection.min - 1)?.paragraphStyle
            ?: richParagraphList.firstOrNull()?.paragraphStyle
            ?: RichParagraph.DefaultParagraphStyle
    )

    private var toAddParagraphStyle: ParagraphStyle by mutableStateOf(ParagraphStyle())
    private var toRemoveParagraphStyle: ParagraphStyle by mutableStateOf(ParagraphStyle())

    /**
     * The current paragraph style.
     * If the selection is collapsed, the paragraph style is the style of the paragraph containing the selection.
     * If the selection is not collapsed, the paragraph style is the style of the selection.
     */
    public val currentParagraphStyle: ParagraphStyle
        get() = currentAppliedParagraphStyle.merge(toAddParagraphStyle).unmerge(toRemoveParagraphStyle)

    private var currentRichParagraphType: ParagraphType by mutableStateOf(
        getRichParagraphByTextIndex(textIndex = selection.min - 1)?.type
            ?: DefaultParagraph()
    )

    public val isUnorderedList: Boolean get() = currentRichParagraphType is UnorderedList
    public val isOrderedList: Boolean get() = currentRichParagraphType is OrderedList

    public val config: RichTextConfig = RichTextConfig(
        updateText = {
            updateTextFieldValue(textFieldValue)
        }
    )

    init {
        updateRichParagraphList(initialRichParagraphList)
    }

    /**
     * Public methods
     */

    @Deprecated(
        message = "Use config instead",
        replaceWith = ReplaceWith("config"),
        level = DeprecationLevel.WARNING,
    )
    public fun setConfig(
        linkColor: Color = Color.Unspecified,
        linkTextDecoration: TextDecoration? = null,
        codeColor: Color = Color.Unspecified,
        codeBackgroundColor: Color = Color.Unspecified,
        codeStrokeColor: Color = Color.Unspecified,
        listIndent: Int = -1
    ) {
        if (linkColor.isSpecified)
            config.linkColor = linkColor

        if (linkTextDecoration != null)
            config.linkTextDecoration = linkTextDecoration

        if (codeColor.isSpecified)
            config.codeSpanColor = codeColor

        if (codeBackgroundColor.isSpecified)
            config.codeSpanBackgroundColor = codeBackgroundColor

        if (codeStrokeColor.isSpecified)
            config.codeSpanStrokeColor = codeStrokeColor

        if (listIndent > -1)
            config.listIndent = listIndent

        updateTextFieldValue(textFieldValue)
    }

    // Text

    /**
     * Removes the selected text from the current text input.
     *
     * This method removes the text specified by the `selection` from the current text input.
     *
     * @see removeTextRange
     */
    public fun removeSelectedText(): Unit =
        removeTextRange(selection)

    /**x
     * Removes the specified text range from the current text.
     *
     * @param textRange the range of text to be removed
     */
    public fun removeTextRange(
        textRange: TextRange
    ) {
        require(textRange.min >= 0) {
            "The start index must be non-negative."
        }

        require(textRange.max <= textFieldValue.text.length) {
            "The end index must be within the text bounds. " +
                    "The text length is ${textFieldValue.text.length}, " +
                    "but the end index is ${textRange.max}."
        }

        onTextFieldValueChange(
            newTextFieldValue = textFieldValue.copy(
                text = textFieldValue.text.removeRange(
                    startIndex = textRange.min,
                    endIndex = textRange.max,
                ),
                selection = TextRange(textRange.min),
            )
        )
    }

    /**
     * Replaces the currently selected text with the provided text.
     *
     * @param text The new text to be inserted
     */
    public fun replaceSelectedText(text: String): Unit =
        replaceTextRange(selection, text)

    /**
     * Replaces the text in the specified range with the provided text.
     *
     * @param textRange The range of text to be replaced
     * @param text The new text to be inserted
     */
    public fun replaceTextRange(
        textRange: TextRange,
        text: String
    ) {
        require(textRange.min >= 0) {
            "The start index must be non-negative."
        }

        require(textRange.max <= textFieldValue.text.length) {
            "The end index must be within the text bounds. " +
                    "The text length is ${textFieldValue.text.length}, " +
                    "but the end index is ${textRange.max}."
        }

        removeTextRange(textRange)
        addTextAfterSelection(text = text)
    }

    /**
     * Adds the provided text to the text field at the current selection.
     *
     * @param text The text to be added
     */
    public fun addTextAfterSelection(text: String): Unit =
        addTextAtIndex(
            index = selection.min,
            text = text
        )

    /**
     * Adds the provided text to the text field at the specified index.
     *
     * @param index The index at which the text should be added
     * @param text The text to be added
     */
    public fun addTextAtIndex(
        index: Int,
        text: String,
    ) {
        require(index >= 0) {
            "The index must be non-negative."
        }

        require(index <= textFieldValue.text.length) {
            "The index must be within the text bounds. " +
                    "The text length is ${textFieldValue.text.length}, " +
                    "but the index is $index."
        }

        val beforeText = textFieldValue.text.substring(0, index)
        val afterText = textFieldValue.text.substring(selection.max)
        val newText = "$beforeText$text$afterText"

        onTextFieldValueChange(
            newTextFieldValue = textFieldValue.copy(
                text = newText,
                selection = TextRange(index + text.length),
            )
        )
    }

    // SpanStyle

    /**
     * Returns the [SpanStyle] of the text at the specified text range.
     * If the text range is collapsed, the style of the character preceding the text range is returned.
     *
     * @param textRange the text range.
     * @return the [SpanStyle] of the text at the specified text range.
     */
    public fun getSpanStyle(textRange: TextRange): SpanStyle =
        if (textRange.collapsed) {
            val richSpan = getRichSpanByTextIndex(textIndex = textRange.min - 1)

            richSpan
                ?.fullSpanStyle
                ?: RichSpanStyle.DefaultSpanStyle
        } else {
            val richSpanList = getRichSpanListByTextRange(textRange)

            richSpanList
                .getCommonStyle()
                ?: RichSpanStyle.DefaultSpanStyle
        }

    /**
     * Returns the [RichSpanStyle] of the text at the specified text range.
     * If the text range is collapsed, the style of the character preceding the text range is returned.
     *
     * @param textRange the text range.
     * @return the [RichSpanStyle] of the text at the specified text range.
     */
    public fun getRichSpanStyle(textRange: TextRange): RichSpanStyle =
        if (textRange.collapsed) {
            val richSpan = getRichSpanByTextIndex(textIndex = textRange.min - 1)

            richSpan
                ?.fullStyle
                ?: RichSpanStyle.Default
        } else {
            val richSpanList = getRichSpanListByTextRange(textRange)

            richSpanList
                .getCommonRichStyle()
                ?: RichSpanStyle.Default
        }

    /**
     * Returns the [ParagraphStyle] of the text at the specified text range.
     * If the text range is collapsed, the style of the paragraph containing the text range is returned.
     *
     * @param textRange the text range.
     * @return the [ParagraphStyle] of the text at the specified text range.
     */
    public fun getParagraphStyle(textRange: TextRange): ParagraphStyle =
        if (textRange.collapsed) {
            val richParagraph = getRichParagraphByTextIndex(textIndex = textRange.min - 1)

            richParagraph
                ?.paragraphStyle
                ?: RichParagraph.DefaultParagraphStyle
        } else {
            val richParagraphList = getRichParagraphListByTextRange(textRange)

            richParagraphList
                .getCommonStyle()
                ?: RichParagraph.DefaultParagraphStyle
        }

    /**
     * Toggle the [SpanStyle]
     * If the passed span style doesn't exist in the [currentSpanStyle] it's going to be added.
     * If the passed span style already exists in the [currentSpanStyle] it's going to be removed.
     *
     * Example: You can toggle Bold FontWeight by passing:
     *
     * ```
     * SpanStyle(fontWeight = FontWeight.Bold)
     * ```
     *
     * @param spanStyle the span style that is going to be toggled.
     * Only the specified params are going to be toggled, and the non specified ones are going to be ignored.
     * @see [addSpanStyle]
     * @see [removeSpanStyle]
     */
    public fun toggleSpanStyle(spanStyle: SpanStyle) {
        if (spanStyle.isUnspecifiedColorSpan()) {
            val modifiedSpan = currentSpanStyle.copy(color = Color.Unspecified)
            removeSpanStyle(currentSpanStyle)
            addSpanStyle(modifiedSpan)
        } else {
            if (currentSpanStyle.isSpecifiedFieldsEquals(spanStyle))
                removeSpanStyle(spanStyle)
            else
                addSpanStyle(spanStyle)
        }
    }

    /**
     * Clear color [SpanStyle]s for current selection.
     */
    public fun clearSelectionColorSpans() {
        annotatedString
            .subSequence(selection)
            .spanStyles
            .filter { it.item.color.isSpecified }
            .forEach {
                val spanStyle = SpanStyle(color = it.item.color)
                toRemoveSpanStyle = toRemoveSpanStyle.customMerge(spanStyle)
                toAddSpanStyle = toAddSpanStyle.unmerge(spanStyle)
                if (!selection.collapsed) applyRichSpanStyleToSelectedText()
            }
    }

    /**
     * Add new [SpanStyle] to the [currentSpanStyle]
     *
     * Example: You can add Bold FontWeight by passing:
     *
     * ```
     * SpanStyle(fontWeight = FontWeight.Bold)
     * ```
     *
     * @param spanStyle the span style that is going to be added to the [currentSpanStyle].
     * Only the specified params are going to be applied, and the non specified ones are going to be ignored.
     * @see [removeSpanStyle]
     * @see [toggleSpanStyle]
     */
    public fun addSpanStyle(spanStyle: SpanStyle) {
        if (!currentSpanStyle.isSpecifiedFieldsEquals(spanStyle)) {
            toAddSpanStyle = toAddSpanStyle.customMerge(spanStyle)
            toRemoveSpanStyle = toRemoveSpanStyle.unmerge(spanStyle)
        }

        if (!selection.collapsed)
            applyRichSpanStyleToSelectedText()
    }

    /**
     * Add new [SpanStyle] for a specific [TextRange]
     *
     * Example: You can add Bold FontWeight to a specific range by passing:
     *
     * ```
     * state.addSpanStyle(SpanStyle(fontWeight = FontWeight.Bold), TextRange(0, 5))
     * ```
     *
     * @param spanStyle the span style that is going to be added to the rich span.
     * @param textRange the text range where the span style is going to be applied.
     */
    public fun addSpanStyle(spanStyle: SpanStyle, textRange: TextRange) {
        val oldToRemoveSpanStyle = toRemoveSpanStyle
        val oldToAddSpanStyle = toAddSpanStyle

        toAddSpanStyle = spanStyle
        toRemoveSpanStyle = SpanStyle()

        applyRichSpanStyleToTextRange(textRange)

        toRemoveSpanStyle = oldToRemoveSpanStyle
        toAddSpanStyle = oldToAddSpanStyle
    }

    /**
     * Remove an existing [SpanStyle] from the [currentSpanStyle]
     *
     * Example: You can remove Bold FontWeight by passing:
     *
     * ```
     * SpanStyle(fontWeight = FontWeight.Bold)
     * ```
     *
     * @param spanStyle the span style that is going to be removed from the [currentSpanStyle].
     * Only the specified params are going to be removed, and the non specified ones are going to be ignored.
     * @see [addSpanStyle]
     * @see [toggleSpanStyle]
     */
    public fun removeSpanStyle(spanStyle: SpanStyle) {
        if (currentSpanStyle.isSpecifiedFieldsEquals(spanStyle)) {
            toRemoveSpanStyle = toRemoveSpanStyle.customMerge(spanStyle)
            toAddSpanStyle = toAddSpanStyle.unmerge(spanStyle)
        }

        if (!selection.collapsed)
            applyRichSpanStyleToSelectedText()
    }

    /**
     * Remove an existing [SpanStyle] from a specific [TextRange]
     *
     * Example: You can remove Bold FontWeight from a specific range by passing:
     *
     * ```
     * state.removeSpanStyle(SpanStyle(fontWeight = FontWeight.Bold), TextRange(0, 5))
     * ```
     *
     * @param spanStyle the span style that is going to be removed from the rich span.
     * @param textRange the text range where the span style is going to be removed.
     */
    public fun removeSpanStyle(spanStyle: SpanStyle, textRange: TextRange) {
        val oldToRemoveSpanStyle = toRemoveSpanStyle
        val oldToAddSpanStyle = toAddSpanStyle

        toRemoveSpanStyle = spanStyle
        toAddSpanStyle = SpanStyle()

        applyRichSpanStyleToTextRange(textRange)

        toRemoveSpanStyle = oldToRemoveSpanStyle
        toAddSpanStyle = oldToAddSpanStyle
    }

    /**
     * Clear all [SpanStyle]s.
     */
    public fun clearSpanStyles() {
        removeSpanStyle(currentSpanStyle)
    }

    /**
     * Clear all [SpanStyle]s from a specific [TextRange].
     */
    public fun clearSpanStyles(textRange: TextRange) {
        removeSpanStyle(currentSpanStyle, textRange)
    }

    // RichSpanStyle

    /**
     * Add a link to the text field.
     * The link is going to be added after the current selection.
     *
     * @param text the text of the link.
     * @param url the URL of the link.
     */
    public fun addLink(
        text: String,
        url: String,
    ) {
        if (text.isEmpty()) return

        val paragraph = richParagraphList.firstOrNull() ?: return
        val linkStyle = RichSpanStyle.Link(
            url = url,
        )
        val linkRichSpan = RichSpan(
            text = text,
            richSpanStyle = linkStyle,
            paragraph = paragraph,
        )

        addRichSpanAtPosition(
            linkRichSpan,
            index = selection.min,
        )

        val beforeText = textFieldValue.text.substring(0, selection.min)
        val afterText = textFieldValue.text.substring(selection.min)
        val newText = "$beforeText$text$afterText"
        updateTextFieldValue(
            newTextFieldValue = textFieldValue.copy(
                text = newText,
                selection = TextRange(selection.min + text.length),
            )
        )
    }

    /**
     * Add a link to the selected text.
     *
     * @param url the URL of the link.
     */
    public fun addLinkToSelection(
        url: String,
    ) {
        if (selection.collapsed) return

        val linkStyle = RichSpanStyle.Link(
            url = url,
        )

        toAddRichSpanStyle = linkStyle
        toRemoveRichSpanStyleKClass = RichSpanStyle.Default::class

        addRichSpan(
            spanStyle = linkStyle
        )
    }

    /**
     * Add a link to a specific [TextRange].
     *
     * @param url the URL of the link.
     * @param textRange the text range where the link is going to be added.
     */
    public fun addLinkToTextRange(
        url: String,
        textRange: TextRange,
    ) {
        if (textRange.collapsed) return

        val linkStyle = RichSpanStyle.Link(
            url = url,
        )

        toAddRichSpanStyle = linkStyle
        toRemoveRichSpanStyleKClass = RichSpanStyle.Default::class

        addRichSpan(
            spanStyle = linkStyle,
            textRange = textRange
        )
    }

    /**
     * Update the link of the selected text.
     *
     * @param url the new URL of the link.
     */
    public fun updateLink(
        url: String,
    ) {
        if (!isLink) return

        val linkStyle = RichSpanStyle.Link(
            url = url,
        )

        val richSpan = getSelectedLinkRichSpan() ?: return

        richSpan.richSpanStyle = linkStyle

        updateTextFieldValue(textFieldValue)
    }

    /**
     * Remove the link from the selected text.
     */
    public fun removeLink() {
        if (!isLink) return

        val richSpan = getSelectedLinkRichSpan() ?: return

        richSpan.richSpanStyle = RichSpanStyle.Default

        updateTextFieldValue(textFieldValue)
    }

    @Deprecated(
        message = "Use toggleCodeSpan instead",
        replaceWith = ReplaceWith("toggleCodeSpan()"),
        level = DeprecationLevel.ERROR,
    )
    public fun toggleCode(): Unit = toggleCodeSpan()

    public fun toggleCodeSpan(): Unit = toggleRichSpan(RichSpanStyle.Code())

    @Deprecated(
        message = "Use addCodeSpan instead",
        replaceWith = ReplaceWith("addCodeSpan()"),
        level = DeprecationLevel.ERROR,
    )
    public fun addCode(): Unit = addCodeSpan()

    public fun addCodeSpan(): Unit = addRichSpan(RichSpanStyle.Code())

    @Deprecated(
        message = "Use removeCodeSpan instead",
        replaceWith = ReplaceWith("removeCodeSpan()"),
        level = DeprecationLevel.ERROR,
    )
    public fun removeCode(): Unit = removeCodeSpan()

    public fun removeCodeSpan(): Unit = removeRichSpan(RichSpanStyle.Code())

    public fun toggleRichSpan(spanStyle: RichSpanStyle) {
        if (isRichSpan(spanStyle::class))
            removeRichSpan(spanStyle)
        else
            addRichSpan(spanStyle)
    }

    /**
     * Add a new [RichSpanStyle] to the selected text or to the text
     * that is going to be typed if the selection is collapsed.
     *
     * @param spanStyle the rich span style that is going to be added.
     */
    public fun addRichSpan(spanStyle: RichSpanStyle) {
        if (toRemoveRichSpanStyleKClass == spanStyle::class)
            toRemoveRichSpanStyleKClass = RichSpanStyle.Default::class
        toAddRichSpanStyle = spanStyle

        if (!selection.collapsed)
            applyRichSpanStyleToSelectedText()
    }

    /**
     * Add a new [RichSpanStyle] to a specific [TextRange].
     *
     * @param spanStyle the rich span style that is going to be added.
     * @param textRange the text range where the rich span style is going to be applied.
     */
    public fun addRichSpan(
        spanStyle: RichSpanStyle,
        textRange: TextRange,
    ) {
        if (textRange.collapsed)
            return

        if (toRemoveRichSpanStyleKClass == spanStyle::class)
            toRemoveRichSpanStyleKClass = RichSpanStyle.Default::class
        toAddRichSpanStyle = spanStyle

        applyRichSpanStyleToTextRange(textRange)
    }

    /**
     * Remove an existing [RichSpanStyle] from the selected text or from the text
     * that is going to be typed if the selection is collapsed.
     *
     * @param spanStyle the rich span style that is going to be removed.
     */
    public fun removeRichSpan(spanStyle: RichSpanStyle) {
        if (toAddRichSpanStyle::class == spanStyle::class)
            toAddRichSpanStyle = RichSpanStyle.Default
        toRemoveRichSpanStyleKClass = spanStyle::class

        if (!selection.collapsed)
            applyRichSpanStyleToSelectedText()
    }

    /**
     * Remove an existing [RichSpanStyle] from a specific [TextRange].
     *
     * @param spanStyle the rich span style that is going to be removed.
     * @param textRange the text range where the rich span style is going to be removed.
     */
    public fun removeRichSpan(
        spanStyle: RichSpanStyle,
        textRange: TextRange
    ) {
        if (textRange.collapsed)
            return

        if (toAddRichSpanStyle::class == spanStyle::class)
            toAddRichSpanStyle = RichSpanStyle.Default
        toRemoveRichSpanStyleKClass = spanStyle::class

        applyRichSpanStyleToTextRange(textRange)
    }

    /**
     * Clear all [RichSpanStyle]s.
     */
    public fun clearRichSpans() {
        removeRichSpan(currentRichSpanStyle)
    }

    /**
     * Clear all [RichSpanStyle]s from a specific [TextRange].
     */
    public fun clearRichSpans(textRange: TextRange) {
        removeRichSpan(currentRichSpanStyle, textRange)
    }

    /**
     * Toggle the [ParagraphStyle]
     * If the passed paragraph style doesn't exist in the [currentParagraphStyle] it's going to be added.
     * If the passed paragraph style already exists in the [currentParagraphStyle] it's going to be removed.
     *
     * Example: You can toggle TextAlign Center by passing:
     *
     * ```
     * ParagraphStyle(textAlign = TextAlign.Center)
     * ```
     *
     * @param paragraphStyle the paragraph style that is going to be toggled.
     * Only the specified params are going to be toggled, and the non specified ones are going to be ignored.
     * @see [addParagraphStyle]
     * @see [removeParagraphStyle]
     */
    public fun toggleParagraphStyle(paragraphStyle: ParagraphStyle) {
        if (currentParagraphStyle.isSpecifiedFieldsEquals(paragraphStyle))
            removeParagraphStyle(paragraphStyle)
        else
            addParagraphStyle(paragraphStyle)
    }

    /**
     * Add new [ParagraphStyle] to the [currentParagraphStyle]
     *
     * Example: You can add TextAlign Center by passing:
     *
     * ```
     * ParagraphStyle(textAlign = TextAlign.Center)
     * ```
     *
     * @param paragraphStyle the paragraph style that is going to be added to the [currentParagraphStyle].
     * Only the specified params are going to be applied, and the non specified ones are going to be ignored.
     * @see [removeParagraphStyle]
     * @see [toggleParagraphStyle]
     */
    public fun addParagraphStyle(paragraphStyle: ParagraphStyle) {
        if (!currentParagraphStyle.isSpecifiedFieldsEquals(paragraphStyle)) {
            // If the selection is collapsed, we add the paragraph style to the paragraph containing the selection
            if (selection.collapsed) {
                val paragraph = getRichParagraphByTextIndex(selection.min - 1) ?: return
                paragraph.paragraphStyle = paragraph.paragraphStyle.merge(paragraphStyle)
            }
            // If the selection is not collapsed, we add the paragraph style to all the paragraphs in the selection
            else {
                val paragraphs = getRichParagraphListByTextRange(selection)
                if (paragraphs.isEmpty()) return
                paragraphs.fastForEach { it.paragraphStyle = it.paragraphStyle.merge(paragraphStyle) }
            }
            // We update the annotated string to reflect the changes
            updateAnnotatedString()
            // We update the current paragraph style to reflect the changes
            updateCurrentParagraphStyle()
        }
    }

    /**
     * Remove an existing [ParagraphStyle] from the [currentParagraphStyle]
     *
     * Example: You can remove TextAlign Center by passing:
     *
     * ```
     * ParagraphStyle(textAlign = TextAlign.Center)
     * ```
     *
     * @param paragraphStyle the paragraph style that is going to be removed from the [currentParagraphStyle].
     * Only the specified params are going to be removed, and the non specified ones are going to be ignored.
     * @see [addParagraphStyle]
     * @see [toggleParagraphStyle]
     */
    public fun removeParagraphStyle(paragraphStyle: ParagraphStyle) {
        if (currentParagraphStyle.isSpecifiedFieldsEquals(paragraphStyle)) {
            // If the selection is collapsed, we remove the paragraph style from the paragraph containing the selection
            if (selection.collapsed) {
                val paragraph = getRichParagraphByTextIndex(selection.min - 1) ?: return
                paragraph.paragraphStyle = paragraph.paragraphStyle.unmerge(paragraphStyle)
            }
            // If the selection is not collapsed, we remove the paragraph style from all the paragraphs in the selection
            else {
                val paragraphs = getRichParagraphListByTextRange(selection)
                if (paragraphs.isEmpty()) return
                paragraphs.fastForEach { it.paragraphStyle = it.paragraphStyle.unmerge(paragraphStyle) }
            }
            // We update the annotated string to reflect the changes
            updateAnnotatedString()
            // We update the current paragraph style to reflect the changes
            updateCurrentParagraphStyle()
        }
    }

    public fun toggleUnorderedList() {
        val paragraphs = getRichParagraphListByTextRange(selection)
        if (paragraphs.isEmpty()) return
        val removeUnorderedList = paragraphs.first().type is UnorderedList
        paragraphs.forEach { paragraph ->
            if (removeUnorderedList) {
                removeUnorderedList(paragraph)
            } else {
                addUnorderedList(paragraph)
            }
        }
    }

    public fun addUnorderedList() {
        val paragraphs = getRichParagraphListByTextRange(selection)

        paragraphs.forEach { paragraph ->
            addUnorderedList(paragraph)
        }
    }

    public fun removeUnorderedList() {
        val paragraphs = getRichParagraphListByTextRange(selection)

        paragraphs.forEach { paragraph ->
            removeUnorderedList(paragraph)
        }
    }

    public fun toggleOrderedList() {
        val paragraphs = getRichParagraphListByTextRange(selection)
        if (paragraphs.isEmpty()) return
        val removeOrderedList = paragraphs.first().type is OrderedList
        paragraphs.forEach { paragraph ->
            if (removeOrderedList) {
                removeOrderedList(paragraph)
            } else {
                addOrderedList(paragraph)
            }
        }
    }

    public fun addOrderedList() {
        val paragraphs = getRichParagraphListByTextRange(selection)

        paragraphs.forEach { paragraph ->
            addOrderedList(paragraph)
        }
    }

    public fun removeOrderedList() {
        val paragraphs = getRichParagraphListByTextRange(selection)

        paragraphs.forEach { paragraph ->
            removeOrderedList(paragraph)
        }
    }

    /**
     * Private/Internal methods
     */

    /**
     * Returns the [ParagraphType] of the text at the specified text range.
     * If the text range is collapsed, the type of the paragraph containing the text range is returned.
     *
     * @param textRange the text range.
     * @return the [ParagraphType] of the text at the specified text range.
     */
    internal fun getParagraphType(textRange: TextRange): ParagraphType =
        if (textRange.collapsed) {
            val richParagraph = getRichParagraphByTextIndex(textIndex = textRange.min - 1)

            richParagraph
                ?.type
                ?: DefaultParagraph()
        } else {
            val richParagraphList = getRichParagraphListByTextRange(textRange)

            richParagraphList
                .getCommonType()
                ?: DefaultParagraph()
        }

    private fun getSelectedLinkRichSpan(): RichSpan? {
        val richSpan = getRichSpanByTextIndex(selection.min - 1)

        return getLinkRichSpan(richSpan)
    }

    private fun addUnorderedList(paragraph: RichParagraph) {
        if (paragraph.type is UnorderedList) return

        val newType = UnorderedList(
            initialIndent = config.unorderedListIndent
        )

        updateParagraphType(
            paragraph = paragraph,
            newType = newType
        )
    }

    private fun removeUnorderedList(paragraph: RichParagraph) {
        if (paragraph.type !is UnorderedList) return

        resetParagraphType(paragraph = paragraph)
    }

    private fun addOrderedList(paragraph: RichParagraph) {
        if (paragraph.type is OrderedList) return
        val index = richParagraphList.indexOf(paragraph)
        if (index == -1) return
        val previousParagraphType = richParagraphList.getOrNull(index - 1)?.type
        val orderedListNumber =
            if (previousParagraphType is OrderedList)
                previousParagraphType.number + 1
            else 1

        val newTextFieldValue = adjustOrderedListsNumbers(
            startParagraphIndex = index + 1,
            startNumber = orderedListNumber + 1,
            textFieldValue = textFieldValue,
        )

        val firstRichSpan = paragraph.getFirstNonEmptyChild()

        val newType = OrderedList(
            number = orderedListNumber,
            initialIndent = config.orderedListIndent,
            startTextSpanStyle = firstRichSpan?.spanStyle ?: SpanStyle(),
        )
        updateTextFieldValue(
            newTextFieldValue = updateParagraphType(
                paragraph = paragraph,
                newType = newType,
                textFieldValue = newTextFieldValue,
            ),
        )
    }

    private fun removeOrderedList(paragraph: RichParagraph) {
        if (paragraph.type !is OrderedList) return
        val index = richParagraphList.indexOf(paragraph)
        if (index == -1) return

        for (i in (index + 1)..richParagraphList.lastIndex) {
            val currentParagraphType = richParagraphList[i].type
            if (currentParagraphType !is OrderedList) break
            currentParagraphType.number = i - index
        }

        resetParagraphType(paragraph = paragraph)
    }

    private fun updateParagraphType(
        paragraph: RichParagraph,
        newType: ParagraphType,
    ) {
        updateTextFieldValue(
            newTextFieldValue = updateParagraphType(
                paragraph = paragraph,
                newType = newType,
                textFieldValue = this.textFieldValue,
            )
        )
    }

    private fun updateParagraphType(
        paragraph: RichParagraph,
        newType: ParagraphType,
        textFieldValue: TextFieldValue,
    ): TextFieldValue {
        val selection = textFieldValue.selection
        val paragraphOldStartTextLength = paragraph.type.startText.length
        val textFieldValueDiff = this.textFieldValue.text.length - textFieldValue.text.length
        val firstNonEmptyChildIndex = paragraph.getFirstNonEmptyChild()?.textRange?.min?.let {
            if (it >= selection.min) it - textFieldValueDiff
            else it
        }
        val paragraphFirstChildStartIndex = firstNonEmptyChildIndex ?: selection.min

        paragraph.type = newType

        // If the paragraph type start text length didn't change, we don't need to update the text field value
        if (paragraphOldStartTextLength == newType.startText.length) return textFieldValue

        val beforeText = textFieldValue.text.substring(0, paragraphFirstChildStartIndex - paragraphOldStartTextLength)
        val afterText = textFieldValue.text.substring(paragraphFirstChildStartIndex)

        val newSelectionMin =
            if (selection.min > paragraphFirstChildStartIndex) selection.min + newType.startText.length - paragraphOldStartTextLength
            else if (selection.min == paragraphFirstChildStartIndex) paragraphFirstChildStartIndex + newType.startText.length - paragraphOldStartTextLength
            else selection.min
        val newSelectionMax =
            if (selection.max > paragraphFirstChildStartIndex) selection.max + newType.startText.length - paragraphOldStartTextLength
            else if (selection.max == paragraphFirstChildStartIndex) paragraphFirstChildStartIndex + newType.startText.length - paragraphOldStartTextLength
            else selection.max

        return textFieldValue.copy(
            text = beforeText + newType.startText + afterText,
            selection = TextRange(
                newSelectionMin,
                newSelectionMax,
            ),
        )
    }

    private fun resetParagraphType(paragraph: RichParagraph) {
        updateParagraphType(paragraph, DefaultParagraph())
    }

    /**
     * Temporarily stores the new text field value, before it is validated.
     */
    private var tempTextFieldValue = textFieldValue

    /**
     * Handles the new text field value.
     *
     * @param newTextFieldValue the new text field value.
     */
    internal fun onTextFieldValueChange(newTextFieldValue: TextFieldValue) {
        tempTextFieldValue = newTextFieldValue

        if (tempTextFieldValue.text.length > textFieldValue.text.length)
            handleAddingCharacters()
        else if (tempTextFieldValue.text.length < textFieldValue.text.length)
            handleRemovingCharacters()
        else if (
            tempTextFieldValue.text == textFieldValue.text &&
            tempTextFieldValue.selection != textFieldValue.selection
        ) {
            val lastPressPosition = this.lastPressPosition
            if (lastPressPosition != null) {
                adjustSelection(lastPressPosition, newTextFieldValue.selection)
                return
            }
        }

        // Update text field value
        updateTextFieldValue()
    }

    /**
     * Handles updating the text field value and all the related states such as the [annotatedString] and [visualTransformation] to reflect the new text field value.
     *
     * @param newTextFieldValue the new text field value.
     */
    private fun updateTextFieldValue(newTextFieldValue: TextFieldValue = tempTextFieldValue) {
        tempTextFieldValue = newTextFieldValue

        if (!singleParagraphMode) {
            // Check for paragraphs
            checkForParagraphs()
        }

        if (
            tempTextFieldValue.text == textFieldValue.text &&
            tempTextFieldValue.selection != textFieldValue.selection
        ) {
            // Update selection
            textFieldValue = tempTextFieldValue
        } else {
            // Update the annotatedString and the textFieldValue with the new values
            updateAnnotatedString(tempTextFieldValue)
        }

        // Clear un-applied styles
        toAddSpanStyle = SpanStyle()
        toRemoveSpanStyle = SpanStyle()
        toAddRichSpanStyle = RichSpanStyle.Default
        toRemoveRichSpanStyleKClass = RichSpanStyle.Default::class

        // Update current span style
        updateCurrentSpanStyle()

        // Update current paragraph style
        updateCurrentParagraphStyle()

        // Clear [tempTextFieldValue]
        tempTextFieldValue = TextFieldValue()
    }

    /**
     * Update the [annotatedString] to reflect the new changes on the [richParagraphList].
     * This method will update the [annotatedString] and the [textFieldValue] to reflect the new changes.
     * If no [newTextFieldValue] is passed, the [textFieldValue] will be used instead.
     *
     * @param newTextFieldValue the new text field value.
     * @see [textFieldValue]
     * @see [annotatedString]
     */
    internal fun updateAnnotatedString(newTextFieldValue: TextFieldValue = textFieldValue) {
        val newText =
            if (singleParagraphMode)
                newTextFieldValue.text
            else
                newTextFieldValue.text.replace('\n', ' ')

        val newStyledRichSpanList = mutableListOf<RichSpan>()

        usedInlineContentMapKeys.clear()

        annotatedString = buildAnnotatedString {
            var index = 0
            richParagraphList.fastForEachIndexed { i, richParagraph ->
                if (index > newText.length) {
                    richParagraphList.removeAt(i)
                    return@fastForEachIndexed
                }

                withStyle(richParagraph.paragraphStyle.merge(richParagraph.type.getStyle(config))) {
                    append(richParagraph.type.startText)
                    val richParagraphStartTextLength = richParagraph.type.startText.length
                    richParagraph.type.startRichSpan.textRange = TextRange(index, index + richParagraphStartTextLength)
                    index += richParagraphStartTextLength
                    withStyle(RichSpanStyle.DefaultSpanStyle) {
                        index = append(
                            state = this@RichTextState,
                            richSpanList = richParagraph.children,
                            startIndex = index,
                            text = newText,
                            selection = newTextFieldValue.selection,
                            onStyledRichSpan = {
                                newStyledRichSpanList.add(it)
                            },
                        )

                        if (!singleParagraphMode) {
                            // Add empty space in the end of each paragraph to fix an issue with Compose TextField
                            // that makes that last char non-selectable when having multiple paragraphs
                            if (i != richParagraphList.lastIndex && index < newText.length) {
                                append(' ')
                                index++
                            }
                        }
                    }
                }
            }
        }

        inlineContentMap.keys.forEach { key ->
            if (key !in usedInlineContentMapKeys) {
                inlineContentMap.remove(key)
            }
        }

        styledRichSpanList.clear()
        textFieldValue = newTextFieldValue.copy(text = annotatedString.text)
        visualTransformation = VisualTransformation { _ ->
            TransformedText(
                text = annotatedString,
                offsetMapping = OffsetMapping.Identity
            )
        }
        styledRichSpanList.addAll(newStyledRichSpanList)
    }

    /**
     * Handles adding characters to the text field.
     * This method will update the [richParagraphList] to reflect the new changes.
     * This method will use the [tempTextFieldValue] to get the new characters.
     */
    private fun handleAddingCharacters() {
        val typedCharsCount = tempTextFieldValue.text.length - textFieldValue.text.length
        var startTypeIndex = textFieldValue.selection.min
        val typedText = tempTextFieldValue.text.substring(
            startIndex = startTypeIndex,
            endIndex = startTypeIndex + typedCharsCount,
        )
        val previousIndex = startTypeIndex - 1

        val activeRichSpan = getOrCreateRichSpanByTextIndex(previousIndex)

        if (activeRichSpan != null) {
            val isAndroidSuggestion =
                activeRichSpan.isLastInParagraph &&
                activeRichSpan.textRange.max == startTypeIndex &&
                tempTextFieldValue.selection.max == startTypeIndex + typedCharsCount + 1

            val typedText =
                if (isAndroidSuggestion)
                    "$typedText "
                else
                    typedText

            if (isAndroidSuggestion) {
                val beforeText =
                    tempTextFieldValue.text.substring(0, startTypeIndex + typedCharsCount)

                val afterText =
                    tempTextFieldValue.text.substring(startTypeIndex + typedCharsCount)

                tempTextFieldValue = tempTextFieldValue.copy(
                    text = "$beforeText $afterText",
                )
            }

            if (startTypeIndex < activeRichSpan.textRange.min) {
                val indexDiff = activeRichSpan.textRange.min - startTypeIndex
                val beforeTypedText = tempTextFieldValue.text.substring(
                    startIndex = 0,
                    endIndex = startTypeIndex,
                )
                val paragraphStartText = tempTextFieldValue.text.substring(
                    startIndex = startTypeIndex + typedCharsCount,
                    endIndex = activeRichSpan.textRange.min + typedCharsCount,
                )
                val afterTypedText = tempTextFieldValue.text.substring(
                    startIndex = activeRichSpan.textRange.min + typedCharsCount,
                    endIndex = tempTextFieldValue.text.length,
                )
                val newTypedText = beforeTypedText + paragraphStartText + typedText + afterTypedText
                tempTextFieldValue = tempTextFieldValue.copy(
                    text = newTypedText,
                    selection = TextRange(
                        (tempTextFieldValue.selection.min + indexDiff).coerceAtLeast(0),
                        (tempTextFieldValue.selection.max + indexDiff).coerceAtMost(newTypedText.length),
                    ),
                )
            }

            startTypeIndex = max(startTypeIndex, activeRichSpan.textRange.min)
            val startIndex = max(0, startTypeIndex - activeRichSpan.textRange.min)
            val beforeText =
                if (activeRichSpan.text.isEmpty())
                    ""
                else
                    activeRichSpan.text.substring(0, startIndex)

            val afterText =
                if (activeRichSpan.text.isEmpty())
                    ""
                else
                    activeRichSpan.text.substring(startIndex)

            val activeRichSpanFullSpanStyle = activeRichSpan.fullSpanStyle
            val newSpanStyle = activeRichSpanFullSpanStyle.customMerge(toAddSpanStyle).unmerge(toRemoveSpanStyle)
            val newRichSpanStyle =
                when {
                    toAddRichSpanStyle !is RichSpanStyle.Default ->
                        toAddRichSpanStyle

                    toRemoveRichSpanStyleKClass == activeRichSpan.richSpanStyle::class ->
                        RichSpanStyle.Default

                    else ->
                        activeRichSpan.richSpanStyle
                }

            val isToAddRemoveSpanStyleEmpty =
                toAddSpanStyle == SpanStyle() && toRemoveSpanStyle == SpanStyle()

            val isToAddRemoveRichSpanStyleEmpty =
                toAddRichSpanStyle is RichSpanStyle.Default && toRemoveRichSpanStyleKClass == RichSpanStyle.Default::class

            if (
                (isToAddRemoveSpanStyleEmpty && isToAddRemoveRichSpanStyleEmpty) ||
                (newSpanStyle == activeRichSpanFullSpanStyle && newRichSpanStyle::class == activeRichSpan.richSpanStyle::class)
            ) {
                activeRichSpan.text = beforeText + typedText + afterText

                checkListStart(richSpan = activeRichSpan)
            } else {
                handleUpdatingRichSpan(
                    richSpan = activeRichSpan,
                    beforeText = beforeText,
                    middleText = typedText,
                    afterText = afterText,
                    startIndex = startTypeIndex,
                    richSpanFullSpanStyle = activeRichSpanFullSpanStyle,
                    newSpanStyle = newSpanStyle,
                )
            }
        } else {
            if (richParagraphList.isEmpty()) {
                richParagraphList.add(RichParagraph())
            }

            val newRichSpan = RichSpan(
                paragraph = richParagraphList.last(),
                text = typedText,
                textRange = TextRange(startTypeIndex, startTypeIndex + typedText.length),
                spanStyle = toAddSpanStyle,
                richSpanStyle = toAddRichSpanStyle,
            )
            richParagraphList.last().children.add(newRichSpan)
        }
    }

    /**
     * Handles removing characters from the text field value.
     * This method will update the [richParagraphList] to reflect the new changes.
     * This method will use the [tempTextFieldValue] to get the removed characters.
     */
    private fun handleRemovingCharacters() {
        val removedCharsCount = textFieldValue.text.length - tempTextFieldValue.text.length

        val minRemoveIndex =
            tempTextFieldValue.selection.min
                .coerceAtLeast(0)

        val maxRemoveIndex =
            (minRemoveIndex + removedCharsCount)
                .coerceAtMost(textFieldValue.text.length)

        val removeRange = TextRange(minRemoveIndex, maxRemoveIndex)

        val minRichSpan = getRichSpanByTextIndex(textIndex = minRemoveIndex, true) ?: return
        val maxRichSpan = getRichSpanByTextIndex(textIndex = maxRemoveIndex - 1, true) ?: return

        // Check deleted paragraphs
        val minParagraphIndex = richParagraphList.indexOf(minRichSpan.paragraph)
        val maxParagraphIndex = richParagraphList.indexOf(maxRichSpan.paragraph)

        // Remove paragraphs between the min and max paragraphs
        if (minParagraphIndex < maxParagraphIndex - 1 && !singleParagraphMode) {
            richParagraphList.removeRange(minParagraphIndex + 1, maxParagraphIndex)
        }

        // Get the first non-empty child of the min paragraph
        val minFirstNonEmptyChild = minRichSpan.paragraph.getFirstNonEmptyChild()
        val minParagraphStartTextLength = minRichSpan.paragraph.type.startRichSpan.text.length
        val minParagraphFirstChildMinIndex = minFirstNonEmptyChild?.textRange?.min ?: minParagraphStartTextLength

        // Get the first non-empty child of the max paragraph
        val maxFirstNonEmptyChild = maxRichSpan.paragraph.getFirstNonEmptyChild()
        val maxParagraphStartTextLength = maxRichSpan.paragraph.type.startRichSpan.text.length
        val maxParagraphFirstChildMinIndex = maxFirstNonEmptyChild?.textRange?.min ?: maxParagraphStartTextLength

        // TODO:
        //  Check if we can remove this condition since we are already checking below
        //  if the paragraph needs to be removed
        if (minParagraphIndex == maxParagraphIndex && !singleParagraphMode) {
            if (
                (minFirstNonEmptyChild == null || minFirstNonEmptyChild.text.isEmpty()) &&
                minRichSpan.paragraph.type.startText.isEmpty()
            ) {
                // Remove the min paragraph if it's empty (and the max paragraph is the same)
                richParagraphList.removeAt(minParagraphIndex)
            }
        }

        // Handle Remove the min paragraph custom text
        if (minRemoveIndex < minParagraphFirstChildMinIndex) {
            if (minRichSpan.paragraph.type.startText.isEmpty() && minParagraphIndex != maxParagraphIndex) {
                richParagraphList.removeAt(minParagraphIndex)
            } else {
                handleRemoveMinParagraphStartText(
                    removeIndex = minRemoveIndex,
                    paragraphStartTextLength = minParagraphStartTextLength,
                    paragraphFirstChildMinIndex = minParagraphFirstChildMinIndex,
                )

                minRichSpan.paragraph.type = DefaultParagraph()

                tempTextFieldValue = adjustOrderedListsNumbers(
                    startParagraphIndex = minParagraphIndex + 1,
                    startNumber = 1,
                    textFieldValue = tempTextFieldValue,
                )
            }
        }

        // Handle Remove the max paragraph custom text
        if (maxRemoveIndex < maxParagraphFirstChildMinIndex) {
            handleRemoveMaxParagraphStartText(
                minRemoveIndex = minRemoveIndex,
                maxRemoveIndex = maxRemoveIndex,
                paragraphStartTextLength = maxParagraphStartTextLength,
                paragraphFirstChildMinIndex = maxParagraphFirstChildMinIndex,
            )

            maxRichSpan.paragraph.type = DefaultParagraph()

            tempTextFieldValue = adjustOrderedListsNumbers(
                startParagraphIndex = maxParagraphIndex + 1,
                startNumber = 1,
                textFieldValue = tempTextFieldValue,
            )
        }

        // Remove spans from the max paragraph
        maxRichSpan.paragraph.removeTextRange(removeRange, maxParagraphFirstChildMinIndex)

        if (!singleParagraphMode) {
            if (maxParagraphIndex != minParagraphIndex) {
                // Remove spans from the min paragraph
                minRichSpan.paragraph.removeTextRange(removeRange, minParagraphFirstChildMinIndex)

                if (maxRichSpan.paragraph.getFirstNonEmptyChild() == null) {
                    // Remove the max paragraph if it's empty
                    richParagraphList.remove(maxRichSpan.paragraph)
                } else if (minRichSpan.paragraph.getFirstNonEmptyChild() == null) {
                    // Set the min paragraph type to the max paragraph type
                    // Since the max paragraph is going to take the min paragraph's place
                    maxRichSpan.paragraph.type = minRichSpan.paragraph.type

                    // Remove the min paragraph if it's empty
                    richParagraphList.remove(minRichSpan.paragraph)
                } else {
                    // Merge the two paragraphs if they are not empty
                    mergeTwoRichParagraphs(
                        firstParagraph = minRichSpan.paragraph,
                        secondParagraph = maxRichSpan.paragraph,
                    )
                }
            }

            if (minRemoveIndex == minParagraphFirstChildMinIndex - minParagraphStartTextLength - 1) {
                if (minParagraphStartTextLength > 0) {
                    val beforeText = tempTextFieldValue.text.substring(
                        startIndex = 0,
                        endIndex = minRemoveIndex
                            .coerceAtMost(tempTextFieldValue.text.length),
                    )
                    val afterText =
                        if (minRemoveIndex + 1 > tempTextFieldValue.text.lastIndex)
                            ""
                        else
                            tempTextFieldValue.text.substring(
                                startIndex = minRemoveIndex + 1,
                            )

                    tempTextFieldValue = tempTextFieldValue.copy(
                        text = beforeText + afterText,
                        selection = TextRange(tempTextFieldValue.selection.min),
                    )
                }

                richParagraphList.getOrNull(minParagraphIndex - 1)?.let { previousParagraph ->
                    // Merge the two paragraphs if the line break is removed
                    mergeTwoRichParagraphs(
                        firstParagraph = previousParagraph,
                        secondParagraph = maxRichSpan.paragraph,
                    )
                }
            }
        }

        // Set current applied style to min rich span if the paragraph is empty
        if (
            config.preserveStyleOnEmptyLine &&
            minRichSpan.paragraph.isEmpty()
        ) {
            val minParagraphFirstRichSpan =
                minRichSpan.paragraph.getFirstNonEmptyChild() ?: run {
                    val richSpan =
                        RichSpan(
                            paragraph = minRichSpan.paragraph,
                            text = "",
                            textRange = TextRange(minRemoveIndex, minRemoveIndex),
                        )

                    minRichSpan.paragraph.children.add(richSpan)

                    richSpan
                }

            minParagraphFirstRichSpan.spanStyle = currentAppliedSpanStyle
            minParagraphFirstRichSpan.richSpanStyle = currentAppliedRichSpanStyle
        }

        checkOrderedListsNumbers(
            startParagraphIndex = minParagraphIndex - 1,
            endParagraphIndex = minParagraphIndex + 1,
        )
    }

    private fun handleRemoveMinParagraphStartText(
        removeIndex: Int,
        paragraphStartTextLength: Int,
        paragraphFirstChildMinIndex: Int,
    ) {
        if (removeIndex >= paragraphFirstChildMinIndex || paragraphStartTextLength <= 0)
            return

        val indexDiff = (paragraphStartTextLength - (paragraphFirstChildMinIndex - removeIndex))
            .coerceAtLeast(0)
        val beforeTextEndIndex =
            (paragraphFirstChildMinIndex - paragraphStartTextLength)
                .coerceAtMost(tempTextFieldValue.text.length)

        val beforeText =
            if (beforeTextEndIndex <= 0)
                ""
            else
                tempTextFieldValue.text.substring(
                    startIndex = 0,
                    endIndex = beforeTextEndIndex,
                )
        val afterText =
            if (tempTextFieldValue.text.length <= removeIndex)
                ""
            else
                tempTextFieldValue.text.substring(
                    startIndex = removeIndex,
                    endIndex = tempTextFieldValue.text.length,
                )
        val newText = beforeText + afterText
        val newSelection = TextRange(removeIndex - indexDiff)

        tempTextFieldValue = tempTextFieldValue.copy(
            text = newText,
            selection = newSelection,
        )
    }

    private fun handleRemoveMaxParagraphStartText(
        minRemoveIndex: Int,
        maxRemoveIndex: Int,
        paragraphStartTextLength: Int,
        paragraphFirstChildMinIndex: Int,
    ) {
        if (maxRemoveIndex < paragraphFirstChildMinIndex && paragraphStartTextLength > 0) {
            paragraphStartTextLength - (paragraphFirstChildMinIndex - maxRemoveIndex)

            val beforeText =
                if (minRemoveIndex <= 0)
                    ""
                else
                    tempTextFieldValue.text.substring(
                        startIndex = 0,
                        endIndex = minRemoveIndex,
                    )

            val afterTextStartIndex = minRemoveIndex + (paragraphFirstChildMinIndex - maxRemoveIndex)

            val afterText =
                if (tempTextFieldValue.text.length <= afterTextStartIndex)
                    ""
                else
                    tempTextFieldValue.text.substring(
                        startIndex = afterTextStartIndex,
                        endIndex = tempTextFieldValue.text.length,
                    )

            val newText = beforeText + afterText

            tempTextFieldValue = tempTextFieldValue.copy(
                text = newText,
            )
        }
    }

    private fun checkListStart(richSpan: RichSpan) {
        if (richSpan.paragraph.type !is DefaultParagraph)
            return

        if (!richSpan.isFirstInParagraph)
            return

        if (richSpan.text == "- " || richSpan.text == "* ") {
            richSpan.paragraph.type = UnorderedList(
                initialIndent = config.unorderedListIndent,
            )
            richSpan.text = ""
        } else if (richSpan.text.matches(Regex("^\\d+\\. "))) {
            val dotIndex = richSpan.text.indexOf('.')
            if (dotIndex != -1) {
                val number = richSpan.text.substring(0, dotIndex).toIntOrNull() ?: 1
                richSpan.paragraph.type = OrderedList(
                    number = number,
                    initialIndent = config.orderedListIndent,
                )
                richSpan.text = ""
            }
        }
    }

    private fun adjustOrderedListsNumbers(
        startParagraphIndex: Int,
        startNumber: Int,
        textFieldValue: TextFieldValue,
    ): TextFieldValue {
        var newTextFieldValue = textFieldValue
        var number = startNumber
        // Update the paragraph type of the paragraphs after the new paragraph
        for (i in (startParagraphIndex)..(richParagraphList.lastIndex)) {
            val currentParagraph = richParagraphList[i]
            val currentParagraphType = currentParagraph.type
            if (currentParagraphType is OrderedList) {
                newTextFieldValue = updateParagraphType(
                    paragraph = currentParagraph,
                    newType = OrderedList(
                        number = number,
                        initialIndent = config.orderedListIndent,
                        startTextSpanStyle = currentParagraphType.startTextSpanStyle,
                        startTextWidth = currentParagraphType.startTextWidth
                    ),
                    textFieldValue = newTextFieldValue,
                )
            } else break
            number++
        }
        return newTextFieldValue
    }

    private fun checkOrderedListsNumbers(
        startParagraphIndex: Int,
        endParagraphIndex: Int,
    ) {
        var number = 1
        val startParagraph = richParagraphList.getOrNull(startParagraphIndex)
        val startParagraphType = startParagraph?.type
        if (startParagraphType is OrderedList) {
            number = startParagraphType.number + 1
        }
        // Update the paragraph type of the paragraphs after the new paragraph
        for (i in (startParagraphIndex + 1)..richParagraphList.lastIndex) {
            val currentParagraph = richParagraphList[i]
            val currentParagraphType = currentParagraph.type
            if (currentParagraphType is OrderedList) {
                tempTextFieldValue = updateParagraphType(
                    paragraph = currentParagraph,
                    newType = OrderedList(
                        number = number,
                        initialIndent = config.orderedListIndent,
                        startTextSpanStyle = currentParagraphType.startTextSpanStyle,
                        startTextWidth = currentParagraphType.startTextWidth
                    ),
                    textFieldValue = tempTextFieldValue,
                )
                number++
            } else if (i >= endParagraphIndex)
                break
            else
                number = 1
        }
    }

    private fun checkForParagraphs() {
        var index = tempTextFieldValue.text.lastIndex

        while (true) {
            // Search for the next paragraph
            index = tempTextFieldValue.text.lastIndexOf('\n', index)

            // If there are no more paragraphs, break
            if (index < textFieldValue.selection.min) break

            // Get the rich span style at the index to split it between two paragraphs
            val richSpan = getRichSpanByTextIndex(index)

            // If there is no rich span style at the index, continue (this should not happen)
            if (richSpan == null) {
                index--
                continue
            }

            // Get the paragraph style index of the rich span style
            val paragraphIndex = richParagraphList.indexOf(richSpan.paragraph)
            // If the paragraph index is -1, continue (this should not happen)
            if (paragraphIndex == -1) {
                index--
                continue
            }

            // Make sure the index is not less than the minimum text range of the rich span style
            // This is to make sure that the index is not in paragraph custom start text
            val sliceIndex = max(index, richSpan.textRange.min)

            // Create a new paragraph style
            val newParagraph = richSpan.paragraph.slice(
                startIndex = sliceIndex,
                richSpan = richSpan,
            )

            // If the new paragraph is empty apply style depending on the config
            if (tempTextFieldValue.selection.collapsed && newParagraph.isEmpty()) {
                val newParagraphFirstRichSpan = newParagraph.getFirstNonEmptyChild()

                val isSelectionAtNewRichSpan =
                    newParagraphFirstRichSpan?.textRange?.min == tempTextFieldValue.selection.min - 1

                // Check if the cursor is at the new paragraph
                if (
                    (!config.preserveStyleOnEmptyLine || richSpan.paragraph.isEmpty()) &&
                    isSelectionAtNewRichSpan
                ) {
                    newParagraphFirstRichSpan.spanStyle = SpanStyle()
                    newParagraphFirstRichSpan.richSpanStyle = RichSpanStyle.Default
                } else if (
                    config.preserveStyleOnEmptyLine &&
                    isSelectionAtNewRichSpan
                ) {
                    newParagraphFirstRichSpan.spanStyle = currentSpanStyle
                    newParagraphFirstRichSpan.richSpanStyle = currentRichSpanStyle
                }
            }

            // Get the text before and after the slice index
            val beforeText = tempTextFieldValue.text.substring(0, sliceIndex + 1)
            val afterText = tempTextFieldValue.text.substring(sliceIndex + 1)

            // Update the text field value to include the new paragraph custom start text
            tempTextFieldValue = tempTextFieldValue.copy(
                text = beforeText + newParagraph.type.startText + afterText,
                selection = TextRange(
                    start = tempTextFieldValue.selection.start + newParagraph.type.startText.length,
                    end = tempTextFieldValue.selection.end + newParagraph.type.startText.length,
                ),
            )

            // Add the new paragraph
            richParagraphList.add(paragraphIndex + 1, newParagraph)

            // Update the paragraph type of the paragraphs after the new paragraph
            val newParagraphType = newParagraph.type

            if (newParagraphType is OrderedList) {
                tempTextFieldValue = adjustOrderedListsNumbers(
                    startParagraphIndex = paragraphIndex + 2,
                    startNumber = newParagraphType.number + 1,
                    textFieldValue = tempTextFieldValue,
                )
            }

            // Remove one from the index to continue searching for paragraphs
            index--
        }
    }

    /**
     * Handles adding or removing the style in [toAddSpanStyle] and [toRemoveSpanStyle] from the selected text.
     */
    private fun applyRichSpanStyleToSelectedText() {
        applyRichSpanStyleToTextRange(selection)
    }

    /**
     * Handles adding or removing the style in [toAddSpanStyle] and [toRemoveSpanStyle] from a given [TextRange].
     *
     * @param textRange The [TextRange] to apply the styles to.
     */
    private fun applyRichSpanStyleToTextRange(
        textRange: TextRange
    ) {
        // Get the rich span list of the selected text
        val selectedRichSpanList = getRichSpanListByTextRange(textRange)

        val startSelectionIndex = textRange.min
        val endSelectionIndex = textRange.max

        // Loop through the rich span list
        for (i in selectedRichSpanList.lastIndex downTo 0) {
            val richSpan = selectedRichSpanList[i]

            // Get the text before, during, and after the selected text
            val beforeText =
                if (startSelectionIndex in richSpan.textRange)
                    richSpan.text.substring(0, startSelectionIndex - richSpan.textRange.start)
                else
                    ""

            val middleText =
                richSpan.text.substring(
                    maxOf(startSelectionIndex - richSpan.textRange.start, 0),
                    minOf(endSelectionIndex - richSpan.textRange.start, richSpan.text.length)
                )

            val afterText =
                if (endSelectionIndex - 1 in richSpan.textRange)
                    richSpan.text.substring(endSelectionIndex - richSpan.textRange.start)
                else
                    ""

            val richSpanFullSpanStyle = richSpan.fullSpanStyle
            val newSpanStyle = richSpanFullSpanStyle.customMerge(toAddSpanStyle).unmerge(toRemoveSpanStyle)

            val startApplyStyleIndex = maxOf(startSelectionIndex, richSpan.textRange.start)

            handleUpdatingRichSpan(
                richSpan = richSpan,
                startIndex = startApplyStyleIndex,
                beforeText = beforeText,
                middleText = middleText,
                afterText = afterText,
                newSpanStyle = newSpanStyle,
            )
        }

        updateTextFieldValue(textFieldValue)
    }

    /**
     * Apply [toAddSpanStyle] and [toRemoveSpanStyle] to a [RichSpan].
     *
     * @param richSpan The [RichSpan] to apply the styles to.
     * @param beforeText The text before applying the styles.
     * @param middleText The text to apply the styles to.
     * @param afterText The text after applying the styles.
     * @param startIndex The start index of the text to apply the styles to.
     * @param richSpanFullSpanStyle The [SpanStyle] of the [RichSpan].
     * @param newSpanStyle The new [SpanStyle] to apply to the [RichSpan].
     */
    private fun handleUpdatingRichSpan(
        richSpan: RichSpan,
        beforeText: String,
        middleText: String,
        afterText: String,
        startIndex: Int,
        richSpanFullSpanStyle: SpanStyle = richSpan.fullSpanStyle,
        newSpanStyle: SpanStyle = richSpanFullSpanStyle.customMerge(toAddSpanStyle).unmerge(toRemoveSpanStyle),
        newRichSpanStyle: RichSpanStyle =
            when {
                toAddRichSpanStyle !is RichSpanStyle.Default ->
                    toAddRichSpanStyle

                toRemoveRichSpanStyleKClass == richSpan.richSpanStyle::class ->
                    RichSpanStyle.Default

                else ->
                    richSpan.richSpanStyle
            },
    ) {
        if (richSpanFullSpanStyle == newSpanStyle && newRichSpanStyle::class == richSpan.richSpanStyle::class) return

        if (
            (toRemoveSpanStyle == SpanStyle() || !richSpanFullSpanStyle.isSpecifiedFieldsEquals(toRemoveSpanStyle)) &&
            (toRemoveRichSpanStyleKClass == RichSpanStyle.Default::class || newRichSpanStyle::class == richSpan.richSpanStyle::class)
        ) {
            applyStyleToRichSpan(
                richSpan = richSpan,
                beforeText = beforeText,
                middleText = middleText,
                afterText = afterText,
                startIndex = startIndex,
            )
        } else {
            handleRemovingStyleFromRichSpan(
                richSpan = richSpan,
                beforeText = beforeText,
                middleText = middleText,
                afterText = afterText,
                startIndex = startIndex,
                richSpanFullSpanStyle = richSpanFullSpanStyle,
                newSpanStyle = newSpanStyle,
                newRichSpanStyle = newRichSpanStyle,
            )
        }
    }

    /**
     * Handles applying a new [SpanStyle] and a new [RichSpanStyle] to a [RichSpan].
     *
     * @param richSpan The [RichSpan] to apply the new [SpanStyle] to.
     * @param beforeText The text before applying the styles.
     * @param middleText The text to apply the styles to.
     * @param afterText The text after applying the styles.
     * @param startIndex The start index of the text to apply the styles to.
     */
    private fun applyStyleToRichSpan(
        richSpan: RichSpan,
        beforeText: String,
        middleText: String,
        afterText: String,
        startIndex: Int,
    ) {
        val fullSpanStyle = richSpan.fullSpanStyle

        // Simplify the richSpan tree if possible, by avoiding creating a new RichSpan.
        if (
            beforeText.isEmpty() &&
            afterText.isEmpty() &&
            richSpan.children.isEmpty()
        ) {
            richSpan.text = middleText
            richSpan.spanStyle = richSpan.spanStyle
                .copy(textDecoration = fullSpanStyle.textDecoration)
                .customMerge(toAddSpanStyle)
            richSpan.richSpanStyle =
                if (toAddRichSpanStyle !is RichSpanStyle.Default)
                    toAddRichSpanStyle
                else
                    richSpan.richSpanStyle

            return
        }

        richSpan.text = beforeText
        val newRichSpan =
            RichSpan(
                paragraph = richSpan.paragraph,
                parent = richSpan,
                text = middleText,
                textRange = TextRange(
                    startIndex,
                    startIndex + middleText.length
                ),
                spanStyle = SpanStyle(textDecoration = fullSpanStyle.textDecoration).customMerge(toAddSpanStyle),
                richSpanStyle =
                if (toAddRichSpanStyle !is RichSpanStyle.Default)
                    toAddRichSpanStyle
                else
                    richSpan.richSpanStyle,
            )

        val parent = richSpan.parent
        val index =
            parent?.children?.indexOf(richSpan) ?: richSpan.paragraph.children.indexOf(richSpan)
        var isRemoved = false

        val isRichSpanStylingEmpty =
            richSpan.spanStyle == SpanStyle() && richSpan.richSpanStyle is RichSpanStyle.Default

        if (middleText.isNotEmpty()) {
            if (
                (isRichSpanStylingEmpty || richSpan.text.isEmpty()) &&
                index != -1 &&
                richSpan.children.isEmpty()
            ) {
                newRichSpan.parent = richSpan.parent

                if (!isRichSpanStylingEmpty) {
                    newRichSpan.spanStyle = richSpan.spanStyle.customMerge(newRichSpan.spanStyle)
                    if (richSpan.richSpanStyle !is RichSpanStyle.Default && newRichSpan.richSpanStyle is RichSpanStyle.Default)
                        newRichSpan.richSpanStyle = richSpan.richSpanStyle
                }

                if (parent != null) {
                    parent.children.add(index + 1, newRichSpan)

                    if (richSpan.text.isEmpty()) {
                        parent.children.removeAt(index)
                        isRemoved = true
                    }
                } else {
                    richSpan.paragraph.children.add(index + 1, newRichSpan)

                    if (richSpan.text.isEmpty()) {
                        richSpan.paragraph.children.removeAt(index)
                        isRemoved = true
                    }
                }
            } else {
                richSpan.children.add(0, newRichSpan)
                newRichSpan.parent = richSpan
            }
        }

        if (afterText.isNotEmpty()) {
            val afterRichSpan =
                RichSpan(
                    paragraph = richSpan.paragraph,
                    parent = richSpan,
                    text = afterText,
                    textRange = TextRange(
                        startIndex + middleText.length,
                        startIndex + middleText.length + afterText.length
                    ),
                )

            if (
                (isRichSpanStylingEmpty || richSpan.text.isEmpty()) &&
                index != -1 &&
                richSpan.children.isEmpty()
            ) {
                afterRichSpan.parent = richSpan.parent

                if (!isRichSpanStylingEmpty) {
                    afterRichSpan.spanStyle = richSpan.spanStyle.customMerge(afterRichSpan.spanStyle)
                    if (richSpan.richSpanStyle !is RichSpanStyle.Default && afterRichSpan.richSpanStyle is RichSpanStyle.Default)
                        afterRichSpan.richSpanStyle = richSpan.richSpanStyle
                }

                val addIndex =
                    if (isRemoved || middleText.isEmpty())
                        index + 1
                    else
                        index + 2

                if (parent != null) {
                    parent.children.add(addIndex, afterRichSpan)

                    if (richSpan.text.isEmpty() && !isRemoved)
                        parent.children.removeAt(index)
                } else {
                    richSpan.paragraph.children.add(addIndex, afterRichSpan)

                    if (richSpan.text.isEmpty() && !isRemoved)
                        richSpan.paragraph.children.removeAt(index)
                }
            } else {
                richSpan.children.add(1, afterRichSpan)
                afterRichSpan.parent = richSpan
            }
        } else {
            val firstRichSpan = richSpan.children.firstOrNull()
            val secondRichSpan = richSpan.children.getOrNull(1)

            if (
                firstRichSpan != null &&
                secondRichSpan != null &&
                firstRichSpan.spanStyle == secondRichSpan.spanStyle &&
                firstRichSpan.richSpanStyle == secondRichSpan.richSpanStyle
            ) {
                firstRichSpan.text += secondRichSpan.text
                firstRichSpan.children.addAll(secondRichSpan.children)
                richSpan.children.removeAt(1)
            }

            if (
                firstRichSpan != null &&
                richSpan.text.isEmpty() &&
                richSpan.children.size == 1
            ) {
                richSpan.text = firstRichSpan.text
                richSpan.spanStyle = richSpan.spanStyle.customMerge(firstRichSpan.spanStyle)
                richSpan.children.clear()
                richSpan.children.addAll(firstRichSpan.children)
            }
        }
    }

    /**
     * Handles removing a [SpanStyle] from a [RichSpan].
     *
     * @param richSpan The [RichSpan] to remove the [SpanStyle] from.
     * @param beforeText The text before removing the styles.
     * @param middleText The text to remove the styles from.
     * @param afterText The text after removing the styles.
     * @param startIndex The start index of the text to remove the styles from.
     * @param richSpanFullSpanStyle The [SpanStyle] of the [RichSpan].
     * @param newSpanStyle The new [SpanStyle] to apply to the [RichSpan].
     * @param newRichSpanStyle The new [RichSpanStyle] to apply to the [RichSpan].
     */
    private fun handleRemovingStyleFromRichSpan(
        richSpan: RichSpan,
        beforeText: String,
        middleText: String,
        afterText: String,
        startIndex: Int,
        richSpanFullSpanStyle: SpanStyle,
        newSpanStyle: SpanStyle,
        newRichSpanStyle: RichSpanStyle,
    ) {
        richSpan.text = beforeText
        val parentRichSpan = richSpan.getClosestRichSpan(newSpanStyle, newRichSpanStyle)
        val newRichSpan = RichSpan(
            paragraph = richSpan.paragraph,
            parent = parentRichSpan,
            text = middleText,
            textRange = TextRange(
                startIndex,
                startIndex + middleText.length
            ),
            spanStyle = newSpanStyle.unmerge(parentRichSpan?.spanStyle),
            richSpanStyle = newRichSpanStyle,
        )
        val afterRichSpan = RichSpan(
            paragraph = richSpan.paragraph,
            parent = parentRichSpan,
            text = afterText,
            textRange = TextRange(
                startIndex + middleText.length,
                startIndex + middleText.length + afterText.length
            ),
            spanStyle = richSpanFullSpanStyle,
            richSpanStyle = richSpan.richSpanStyle,
        )

        val toShiftRichSpanList: MutableList<RichSpan> = mutableListOf()
        var previousRichSpan: RichSpan?
        var currentRichSpan: RichSpan? = richSpan

        toShiftRichSpanList.add(newRichSpan)
        if (afterRichSpan.text.isNotEmpty())
            toShiftRichSpanList.add(afterRichSpan)

        while (true) {
            previousRichSpan = currentRichSpan
            currentRichSpan = currentRichSpan?.parent

            if (currentRichSpan == null || currentRichSpan == parentRichSpan) {
                break
            } else {
                val index = currentRichSpan.children.indexOf(previousRichSpan)
                if (index in 0 until currentRichSpan.children.lastIndex) {
                    val currentRichSpanFullSpanStyle = currentRichSpan.fullSpanStyle
                    ((index + 1)..currentRichSpan.children.lastIndex).forEach {
                        val childRichSpan = currentRichSpan.children[it]

                        // Ignore shifting empty RichSpan.
                        if (childRichSpan.text.isEmpty() && childRichSpan.children.isEmpty()) {
                            return@forEach
                        }

                        // Merge RichSpan span style with parent RichSpan span style.
                        childRichSpan.spanStyle = currentRichSpanFullSpanStyle.merge(childRichSpan.spanStyle)

                        // Lookup for RichSpan with the same span style and merge them to optimize the RichSpan tree.
                        val lastChild = toShiftRichSpanList.lastOrNull()
                        if (lastChild != null && lastChild.spanStyle == childRichSpan.spanStyle) {
                            if (lastChild.children.isEmpty()) {
                                lastChild.text += childRichSpan.text
                                lastChild.children.addAll(childRichSpan.children)
                            } else {
                                lastChild.children.add(childRichSpan)
                                childRichSpan.parent = lastChild
                                childRichSpan.spanStyle = RichSpanStyle.DefaultSpanStyle
                                for (i in childRichSpan.children.lastIndex downTo 0) {
                                    val child = childRichSpan.children[i]
                                    child.parent = lastChild
                                    childRichSpan.children.removeAt(i)
                                    lastChild.children.add(child)
                                }
                            }
                        } else {
                            childRichSpan.parent = parentRichSpan
                            toShiftRichSpanList.add(childRichSpan)
                        }
                    }

                    // Remove shifted RichSpan from parent RichSpan.
                    currentRichSpan.children.removeRange(index + 1, currentRichSpan.children.size)

                    // Remove empty RichSpan.
                    if (previousRichSpan?.isEmpty() == true) {
                        currentRichSpan.children.removeAt(index)
                    }
                }
            }
        }

        if (parentRichSpan == null || currentRichSpan == null) {
            val index = richSpan.paragraph.children.indexOf(previousRichSpan)
            if (index in 0..richSpan.paragraph.children.lastIndex) {
                richSpan.paragraph.children.addAll(
                    index + 1,
                    toShiftRichSpanList
                )
            }

            // Remove empty RichSpan.
            if (previousRichSpan?.isEmpty() == true) {
                richSpan.paragraph.children.removeAt(index)
            }
        } else {
            val index = parentRichSpan.children.indexOf(previousRichSpan)
            if (index in 0..parentRichSpan.children.lastIndex) {
                parentRichSpan.children.addAll(
                    index + 1,
                    toShiftRichSpanList
                )
            }

            // Remove empty RichSpan.
            if (previousRichSpan?.isEmpty() == true) {
                parentRichSpan.children.removeAt(index)
            }
        }

        if (richSpan.text.isEmpty() && richSpan.children.isEmpty()) {
            val parent = richSpan.parent
            if (parent != null) {
                parent.children.remove(richSpan)
            } else {
                richSpan.paragraph.children.remove(richSpan)
            }
        }
    }

    private fun addRichSpanAtPosition(
        vararg richSpan: RichSpan,
        index: Int,
    ) {
        val previousIndex = index - 1

        val activeRichSpan = getRichSpanByTextIndex(previousIndex)

        // If there is no active rich span, add the rich span to the last paragraph
        if (activeRichSpan == null) {
            richParagraphList.last().children.addAll(richSpan)
            return
        }

        val startIndex = max(0, index - activeRichSpan.textRange.min)
        val beforeText = activeRichSpan.text.substring(0, startIndex)
        val afterText = activeRichSpan.text.substring(startIndex)

        // Simplify the richSpan tree if possible, by avoiding creating a new RichSpan.
        if (
            beforeText.isEmpty() &&
            afterText.isEmpty() &&
            activeRichSpan.children.isEmpty() &&
            richSpan.size == 1
        ) {
            activeRichSpan.text = richSpan.first().text
            activeRichSpan.richSpanStyle = richSpan.first().richSpanStyle
            return
        }

        activeRichSpan.text = beforeText

        var addedTextLength = 0
        for (i in richSpan.lastIndex downTo 0) {
            val newRichSpan = richSpan[i]
            newRichSpan.paragraph = activeRichSpan.paragraph
            newRichSpan.parent = activeRichSpan
            activeRichSpan.children.add(
                0,
                newRichSpan
            )
            addedTextLength += newRichSpan.text.length
        }
        if (afterText.isNotEmpty()) {
            activeRichSpan.children.add(
                1,
                RichSpan(
                    paragraph = activeRichSpan.paragraph,
                    parent = activeRichSpan,
                    text = afterText,
                    textRange = TextRange(
                        index + addedTextLength,
                        index + addedTextLength + afterText.length
                    ),
                )
            )
        } else {
            val firstRichSpan = activeRichSpan.children.firstOrNull()
            val secondRichSpan = activeRichSpan.children.getOrNull(1)

            if (
                firstRichSpan != null &&
                secondRichSpan != null &&
                firstRichSpan.spanStyle == secondRichSpan.spanStyle
            ) {
                firstRichSpan.text += secondRichSpan.text
                firstRichSpan.children.addAll(secondRichSpan.children)
                activeRichSpan.children.removeAt(1)
            }

            if (
                firstRichSpan != null &&
                activeRichSpan.text.isEmpty() &&
                activeRichSpan.children.size == 1 &&
                richSpan.size == 1
            ) {
                activeRichSpan.text = firstRichSpan.text
                activeRichSpan.spanStyle = richSpan.first().spanStyle.customMerge(firstRichSpan.spanStyle)
                activeRichSpan.children.clear()
                activeRichSpan.children.addAll(firstRichSpan.children)
            }
        }
    }

    private fun getToShiftRichSpanList(
        startRichSpan: RichSpan,
        endRichSpan: RichSpan?,
    ): List<RichSpan> {
        val toShiftRichSpanList: MutableList<RichSpan> = mutableListOf()
        var previousRichSpan: RichSpan?
        var currentRichSpan: RichSpan? = startRichSpan

        while (true) {
            previousRichSpan = currentRichSpan
            currentRichSpan = currentRichSpan?.parent

            if (currentRichSpan == null || currentRichSpan == endRichSpan) {
                break
            } else {
                val index = currentRichSpan.children.indexOf(previousRichSpan)
                if (index in 0 until currentRichSpan.children.lastIndex) {
                    ((index + 1)..currentRichSpan.children.lastIndex).forEach {
                        val richSpan = currentRichSpan.children[it]
                        richSpan.spanStyle = richSpan.fullSpanStyle
                        richSpan.parent = endRichSpan
                        toShiftRichSpanList.add(richSpan)
                    }
                    currentRichSpan.children.removeRange(index + 1, currentRichSpan.children.size)
                }
            }
        }

        return toShiftRichSpanList
    }

    /**
     * Slice [RichParagraph] by [startIndex] and [richSpan] that contains [startIndex].
     * The passed [RichParagraph] will be modified, containing only the text before [startIndex].
     * And the new [RichParagraph] will be returned, containing the text after [startIndex].
     *
     * @param startIndex The start index of the slice.
     * @param richSpan The [RichSpan] that contains [startIndex].
     * @return The new [RichParagraph].
     */
    private fun RichParagraph.slice(
        startIndex: Int,
        richSpan: RichSpan,
    ): RichParagraph {
        val newRichParagraph = RichParagraph(
            paragraphStyle = paragraphStyle,
            type = type.getNextParagraphType(),
        )

        var previousRichSpan: RichSpan
        var currentRichSpan: RichSpan = richSpan

        val textStartIndex =
            if (startIndex == type.startRichSpan.textRange.min)
                startIndex - richSpan.textRange.min + type.startRichSpan.text.length
            else
                startIndex - richSpan.textRange.min

        newRichParagraph.type.startRichSpan.paragraph = newRichParagraph
        newRichParagraph.type.startRichSpan.textRange = TextRange(
            0,
            newRichParagraph.type.startRichSpan.text.length
        )

        val beforeText =
            richSpan.text.substring(
                startIndex = 0,
                endIndex = textStartIndex
                    .coerceIn(0, richSpan.text.length)
            )
        val afterText =
            richSpan.text.substring(
                startIndex = (textStartIndex + 1)
                    .coerceIn(0, richSpan.text.length)
            )

        richSpan.text = beforeText
        richSpan.textRange = TextRange(
            richSpan.textRange.min,
            richSpan.textRange.min + beforeText.length
        )

        // We don't copy the current rich span style to the new rich span
        val newRichSpan = RichSpan(
            paragraph = newRichParagraph,
            parent = null,
            text = afterText,
            textRange = TextRange(
                startIndex,
                startIndex + afterText.length
            ),
            spanStyle = richSpan.fullSpanStyle,
        )

        newRichParagraph.children.add(newRichSpan)

        for (i in richSpan.children.lastIndex downTo 0) {
            val childRichSpan = richSpan.children[i]
            richSpan.children.removeAt(i)
            childRichSpan.parent = newRichSpan
            childRichSpan.paragraph = newRichParagraph
            newRichSpan.children.add(childRichSpan)
        }

        while (true) {
            previousRichSpan = currentRichSpan
            currentRichSpan = currentRichSpan.parent ?: break

            val index = currentRichSpan.children.indexOf(previousRichSpan)
            if (index in 0 until currentRichSpan.children.lastIndex) {
                ((index + 1)..currentRichSpan.children.lastIndex).forEach {
                    val childRichSpan = currentRichSpan.children[it]
                    childRichSpan.spanStyle = childRichSpan.fullSpanStyle
                    childRichSpan.parent = null
                    childRichSpan.paragraph = newRichParagraph
                    newRichParagraph.children.add(childRichSpan)
                }
                currentRichSpan.children.removeRange(index + 1, currentRichSpan.children.size)
            }
        }

        val index = richSpan.paragraph.children.indexOf(previousRichSpan)
        if (index in 0 until richSpan.paragraph.children.lastIndex) {
            ((index + 1)..richSpan.paragraph.children.lastIndex).forEach {
                val childRichSpan = richSpan.paragraph.children[it]
                childRichSpan.spanStyle = childRichSpan.fullSpanStyle
                childRichSpan.parent = null
                childRichSpan.paragraph = newRichParagraph
                newRichParagraph.children.add(childRichSpan)
            }
            richSpan.paragraph.children.removeRange(index + 1, richSpan.paragraph.children.size)
        }

        return newRichParagraph
    }

    /**
     * Slice [RichSpan] by [startIndex] and [richSpan] that contains [startIndex].
     * The passed [RichSpan] will be modified, containing only the text before [startIndex].
     * And the new [RichSpan] will be returned, containing the text after [startIndex].
     *
     * @param startIndex The start index of the slice.
     * @param richSpan The [RichSpan] that contains [startIndex].
     * @return The new [RichSpan].
     */
    private fun RichSpan.slice(
        startIndex: Int,
        richSpan: RichSpan,
    ): RichSpan {
        val newRichSpan = RichSpan(
            paragraph = richSpan.paragraph,
        )

        var previousRichSpan: RichSpan
        var currentRichSpan: RichSpan = richSpan

        val textStartIndex = startIndex - richSpan.textRange.min
        val beforeText = if (textStartIndex > 0) richSpan.text.substring(0, textStartIndex) else "" // + ' '
        val afterText = richSpan.text.substring(textStartIndex + 1)

        richSpan.text = beforeText
        richSpan.textRange = TextRange(
            richSpan.textRange.min,
            richSpan.textRange.min + beforeText.length
        )

        val afterRichSpan = RichSpan(
            paragraph = richSpan.paragraph,
            parent = newRichSpan,
            text = afterText,
            textRange = TextRange(
                startIndex,
                startIndex + afterText.length
            ),
            spanStyle = richSpan.fullSpanStyle,
        )

        newRichSpan.children.add(afterRichSpan)

        for (i in richSpan.children.lastIndex downTo 0) {
            val childRichSpan = richSpan.children[i]
            richSpan.children.removeAt(i)
            childRichSpan.parent = afterRichSpan
            afterRichSpan.children.add(childRichSpan)
        }

        while (true) {
            previousRichSpan = currentRichSpan
            currentRichSpan = currentRichSpan.parent ?: break

            val index = currentRichSpan.children.indexOf(previousRichSpan)
            if (index in 0 until currentRichSpan.children.lastIndex) {
                ((index + 1)..currentRichSpan.children.lastIndex).forEach {
                    val childRichSpan = currentRichSpan.children[it]
                    childRichSpan.spanStyle = childRichSpan.fullSpanStyle
                    childRichSpan.parent = null
                    newRichSpan.children.add(childRichSpan)
                }
                currentRichSpan.children.removeRange(index + 1, currentRichSpan.children.size)
            }
        }

        val index = richSpan.paragraph.children.indexOf(previousRichSpan)
        if (index in 0 until richSpan.paragraph.children.lastIndex) {
            ((index + 1)..richSpan.paragraph.children.lastIndex).forEach {
                val childRichSpan = richSpan.paragraph.children[it]
                childRichSpan.spanStyle = childRichSpan.fullSpanStyle
                childRichSpan.parent = null
                newRichSpan.children.add(childRichSpan)
            }
            richSpan.paragraph.children.removeRange(index + 1, richSpan.paragraph.children.size)
        }

        return newRichSpan
    }

    /**
     * Merges two [RichParagraph]s into one.
     * The [firstParagraph] will be modified, containing the text of both [firstParagraph] and [secondParagraph].
     * And the [secondParagraph] will be removed.
     *
     * @param firstParagraph The first [RichParagraph].
     * @param secondParagraph The second [RichParagraph].
     */
    private fun mergeTwoRichParagraphs(
        firstParagraph: RichParagraph,
        secondParagraph: RichParagraph,
    ) {
        // Update the children paragraph of the second paragraph to the first paragraph.
        secondParagraph.updateChildrenParagraph(firstParagraph)

        // Add the children of the second paragraph to the first paragraph.
        firstParagraph.children.addAll(secondParagraph.children)

        // Remove the second paragraph from the rich paragraph list.
        richParagraphList.remove(secondParagraph)
    }

    /**
     * Updates the [currentAppliedSpanStyle] to the [SpanStyle] that should be applied to the current selection.
     */
    private fun updateCurrentSpanStyle() {
        if (selection.collapsed) {
            val richSpan = getRichSpanByTextIndex(textIndex = selection.min - 1)

            currentAppliedRichSpanStyle = richSpan
                ?.fullStyle
                ?: RichSpanStyle.Default
            currentAppliedSpanStyle = richSpan
                ?.fullSpanStyle
                ?: RichSpanStyle.DefaultSpanStyle

//            if (
//                config.preserveStyleOnEmptyLine &&
//                (richSpan == null || (richSpan.isFirstInParagraph && richSpan.paragraph.isEmpty()))
//            ) {
//                val paragraphBefore =
//                    if (selection.min - 2 < 0)
//                        null
//                    else
//                        getRichParagraphByTextIndex(selection.min - 2)
//
//                if (paragraphBefore == null || paragraphBefore.isNotEmpty()) {
//                    toAddRichSpanStyle = currentRichSpanStyle
//                    toAddSpanStyle = currentSpanStyle
//                }
//            }
        } else {
            val richSpanList = getRichSpanListByTextRange(selection)

            currentAppliedRichSpanStyle = richSpanList
                .getCommonRichStyle()
                ?: RichSpanStyle.Default
            currentAppliedSpanStyle = richSpanList
                .getCommonStyle()
                ?: RichSpanStyle.DefaultSpanStyle
        }
    }

    /**
     * Gets the common [RichSpanStyle] of the [RichSpan]s in the [textRange].
     *
     * @param textRange The [TextRange] to get the common [RichSpanStyle] from.
     * @return The common [RichSpanStyle] of the [RichSpan]s in the [textRange].
     */
    private fun getCommonRichSpanStyleByTextRange(
        textRange: TextRange,
    ): RichSpanStyle {
        val richSpanList = getRichSpanListByTextRange(textRange)

        return richSpanList.getCommonRichStyle() ?: RichSpanStyle.Default
    }

    /**
     * Gets the common [SpanStyle] of the [RichSpan]s in the [textRange].
     *
     * @param textRange The [TextRange] to get the common [SpanStyle] from.
     * @return The common [SpanStyle] of the [RichSpan]s in the [textRange].
     */
    private fun getCommonSpanStyleByTextRange(
        textRange: TextRange,
    ): SpanStyle {
        val richSpanList = getRichSpanListByTextRange(textRange)

        return richSpanList.getCommonStyle() ?: RichSpanStyle.DefaultSpanStyle
    }

    /**
     * Updates the [currentAppliedParagraphStyle] to the [ParagraphStyle] that should be applied to the current selection.
     */
    private fun updateCurrentParagraphStyle() {
        if (selection.collapsed) {
            val richParagraph = getRichParagraphByTextIndex(selection.min - 1)

            currentRichParagraphType = richParagraph?.type
                ?: richParagraphList.firstOrNull()?.type
                        ?: DefaultParagraph()
            currentAppliedParagraphStyle = richParagraph?.paragraphStyle
                ?: richParagraphList.firstOrNull()?.paragraphStyle
                        ?: RichParagraph.DefaultParagraphStyle
        } else {
            val richParagraphList = getRichParagraphListByTextRange(selection)

            currentRichParagraphType = richParagraphList
                .getCommonType()
                ?: DefaultParagraph()
            currentAppliedParagraphStyle = richParagraphList
                .getCommonStyle()
                ?: ParagraphStyle()
        }
    }

    internal fun onTextLayout(
        textLayoutResult: TextLayoutResult,
        density: Density,
        maxLines: Int = Int.MAX_VALUE,
    ) {
        this.textLayoutResult = textLayoutResult
        adjustRichParagraphLayout(
            density = density,
            maxLines = maxLines,
        )
    }

    private fun adjustRichParagraphLayout(
        density: Density,
        maxLines: Int,
    ) {
        var isParagraphUpdated = false

        textLayoutResult?.let { textLayoutResult ->
            richParagraphList.forEachIndexed { index, richParagraph ->
                val paragraphType = richParagraph.type
                if (index + 1 > maxLines || paragraphType !is ConfigurableStartTextWidth)
                    return@forEachIndexed

                if (
                    paragraphType.startText.isNotEmpty() &&
                    paragraphType.startRichSpan.textRange.max <= textLayoutResult.layoutInput.text.text.length
                ) {
                    val start =
                        textLayoutResult.getHorizontalPosition(
                            offset = paragraphType.startRichSpan.textRange.min,
                            usePrimaryDirection = true
                        )
                    val end =
                        textLayoutResult.getHorizontalPosition(
                            offset = paragraphType.startRichSpan.textRange.max,
                            usePrimaryDirection = true
                        )
                    val distanceSp =
                        with(density) {
                            (end - start).toSp()
                        }

                    if (paragraphType.startTextWidth != distanceSp) {
                        paragraphType.startTextWidth = distanceSp
                        isParagraphUpdated = true
                    }
                }
            }
        }

        if (isParagraphUpdated)
            updateTextFieldValue(textFieldValue)
    }

    internal fun getLinkByOffset(offset: Offset): String? {
        val richSpan = getRichSpanByOffset(offset)
        val linkRichSpan = getLinkRichSpan(richSpan)

        return (linkRichSpan?.richSpanStyle as? RichSpanStyle.Link)?.url
    }

    internal fun isLink(offset: Offset): Boolean {
        val richSpan = getRichSpanByOffset(offset)
        val linkRichSpan = getLinkRichSpan(richSpan)

        return linkRichSpan != null
    }

    private fun getLinkRichSpan(initialRichSpan: RichSpan?): RichSpan? {
        var richSpan = initialRichSpan

        while (richSpan != null && richSpan.richSpanStyle !is RichSpanStyle.Link) {
            richSpan = richSpan.parent
        }

        return richSpan
    }

    private fun getRichSpanByOffset(offset: Offset): RichSpan? {
        this.textLayoutResult?.let { textLayoutResult ->
            val position = textLayoutResult.getOffsetForPosition(offset)
            return getRichSpanByTextIndex(position, true)
        }
        return null
    }

    /**
     * Adjusts the [selection] to the [pressPosition].
     * This is a workaround for the [TextField] that the [selection] is not always correct when you have multiple lines.
     *
     * @param pressPosition The press position.
     */
    internal suspend fun adjustSelectionAndRegisterPressPosition(
        pressPosition: Offset,
    ) {
        adjustSelection(pressPosition)
        registerLastPressPosition(pressPosition)
    }

    /**
     * Adjusts the [selection] to the [pressPosition].
     * This is a workaround for the [TextField] that the [selection] is not always correct when you have multiple lines.
     *
     * @param pressPosition The press position.
     * @param newSelection The new selection.
     */
    private fun adjustSelection(
        pressPosition: Offset,
        newSelection: TextRange? = null,
    ) {
        val selection = newSelection ?: this.selection
        var pressX = pressPosition.x
        var pressY = pressPosition.y
        val textLayoutResult = this.textLayoutResult ?: return
        var index = 0
        var lastIndex = 0

        // Get the length of the text
        val textLength = textLayoutResult.layoutInput.text.length

        // Ensure pressY is within valid bounds
        pressY = pressY.coerceIn(0f, textLayoutResult.size.height.toFloat())

        for (i in 0 until textLayoutResult.lineCount) {
            val start = textLayoutResult.getLineStart(i)
            val top = textLayoutResult.getLineTop(i)

            if (i == 0) {
                if (start > 0f) {
                    pressX += start
                }

                if (top > 0f) {
                    pressY += top
                }
            }

            // Make sure pressY is within the current line's top position
            if (i == 0 && top > pressY) {
                break
            }

            if (top > pressY) {
                index = lastIndex
                break
            }

            lastIndex = index

            if (textLayoutResult.layoutInput.text.text.lastIndex == -1)
                break

            richParagraphList.getOrNull(index)?.let { paragraph ->
                val textRange = paragraph.getTextRange().coerceIn(
                    0, textLayoutResult.layoutInput.text.text.lastIndex
                )

                val pStartTop = textLayoutResult.getBoundingBox(textRange.min).top
                val pEndTop = textLayoutResult.getBoundingBox(textRange.max).top

                val pStartEndTopDiff = (pStartTop - pEndTop).absoluteValue
                val pEndTopLTopDiff = (pEndTop - top).absoluteValue

                if (pStartEndTopDiff < 2f || pEndTopLTopDiff < 2f || pEndTop < top) {
                    index++
                }
            }
        }

        if (index > richParagraphList.lastIndex)
            index = richParagraphList.lastIndex

        val selectedParagraph = richParagraphList.getOrNull(index) ?: return
        val nextParagraph = richParagraphList.getOrNull(index + 1)
        val nextParagraphStart =
            if (nextParagraph == null)
                    null
            else
                (nextParagraph.getFirstNonEmptyChild() ?: nextParagraph.type.startRichSpan)
                    .textRange.min.minus(nextParagraph.type.startText.length)

        // Handle selection adjustments
        if (
            selection.collapsed &&
            selection.min == nextParagraphStart
        ) {
            updateTextFieldValue(
                textFieldValue.copy(
                    selection = TextRange((selection.min - 1).coerceAtLeast(0), (selection.min - 1).coerceAtLeast(0))
                )
            )
        } else if (
            selection.collapsed &&
            index == richParagraphList.lastIndex &&
            selectedParagraph.isEmpty() &&
            selection.min == selectedParagraph.getFirstNonEmptyChild()?.textRange?.min?.minus(1)
        ) {
            updateTextFieldValue(
                textFieldValue.copy(
                    selection = TextRange((selection.min + 1).coerceAtMost(textLength - 1), (selection.min + 1).coerceAtMost(textLength - 1))
                )
            )
        } else if (newSelection != null) {
            // Ensure newSelection is within valid bounds
            val adjustedSelection = TextRange(
                newSelection.start.coerceIn(0, textLength),
                newSelection.end.coerceIn(0, textLength)
            )
            updateTextFieldValue(
                textFieldValue.copy(
                    selection = adjustedSelection
                )
            )
        }
    }

    private var registerLastPressPositionJob: Job? = null
    private suspend fun registerLastPressPosition(pressPosition: Offset): Unit = coroutineScope {
        registerLastPressPositionJob?.cancel()
        registerLastPressPositionJob = launch {
            lastPressPosition = pressPosition
            delay(300)
            lastPressPosition = null
        }
    }

    /**
     * Returns the [RichParagraph] that contains the given [textIndex].
     * If no [RichParagraph] contains the given [textIndex], null is returned.
     *
     * @param textIndex The text index to search for.
     * @return The [RichParagraph] that contains the given [textIndex], or null if no such [RichParagraph] exists.
     */
    private fun getRichParagraphByTextIndex(
        textIndex: Int,
    ): RichParagraph? {
        if (singleParagraphMode || textIndex < 0)
            return richParagraphList.firstOrNull()

        var index = 0
        var paragraphIndex = -1
        return richParagraphList.fastFirstOrNull { richParagraphStyle ->
            paragraphIndex++
            val result = richParagraphStyle.getRichSpanByTextIndex(
                paragraphIndex = paragraphIndex,
                textIndex = textIndex,
                offset = index,
            )
            index = result.first
            result.second != null
        }
    }

    /**
     * Returns a list of [RichParagraph]s that contains at least a part of the given [searchTextRange].
     * If no [RichParagraph] contains at least a part of the given [searchTextRange], an empty list is returned.
     *
     * @param searchTextRange The [TextRange] to search for.
     * @return A list of [RichParagraph]s that contains a part of the given [searchTextRange],
     * or an empty list if no such [RichParagraph] exists.
     */
    private fun getRichParagraphListByTextRange(searchTextRange: TextRange): List<RichParagraph> {
        if (singleParagraphMode) return richParagraphList.toList()

        var index = 0
        val richParagraphList = mutableListOf<RichParagraph>()
        this.richParagraphList.fastForEachIndexed { paragraphIndex, richParagraphStyle ->
            val result = richParagraphStyle.getRichSpanListByTextRange(
                paragraphIndex = paragraphIndex,
                searchTextRange = searchTextRange,
                offset = index,
            )

            val paragraphStartIndex =
                if (paragraphIndex == 0)
                    0
                else if (searchTextRange.collapsed)
                    index + 1
                // If the search text range is not collapsed, we need to ignore the first index of the paragraph.
                // Because the first index of the paragraph is the last index of the previous paragraph.
                else
                    index + 2

            val isCursorInParagraph =
                searchTextRange.min in paragraphStartIndex..result.first ||
                        searchTextRange.max in paragraphStartIndex..result.first

            if (result.second.isNotEmpty() || isCursorInParagraph)
                richParagraphList.add(richParagraphStyle)

            index = result.first
        }
        return richParagraphList
    }

    private fun getOrCreateRichSpanByTextIndex(
        textIndex: Int,
        ignoreCustomFiltering: Boolean = false,
    ): RichSpan? {
        val richSpan =
            getRichSpanByTextIndex(
                textIndex = textIndex,
                ignoreCustomFiltering = ignoreCustomFiltering,
            )

        if (richSpan == null && textIndex < 0) {
            val firstParagraph = richParagraphList.firstOrNull() ?: return null
            val newRichSpan = RichSpan(
                paragraph = firstParagraph,
                text = "",
            )
            firstParagraph.children.add(0, newRichSpan)
            return newRichSpan
        }

        return richSpan
    }

    /**
     * Returns the [RichSpan] that contains the given [textIndex].
     * If no [RichSpan] contains the given [textIndex], null is returned.
     *
     * @param textIndex The text index to search for.
     * @return The [RichSpan] that contains the given [textIndex], or null if no such [RichSpan] exists.
     */
    private fun getRichSpanByTextIndex(
        textIndex: Int,
        ignoreCustomFiltering: Boolean = false,
    ): RichSpan? {
        // If the text index is equal or less than 0, we can return the first non-empty child of the first paragraph.
        if (textIndex < 0) {
            val firstParagraph = richParagraphList.firstOrNull() ?: return null
            return firstParagraph.getFirstNonEmptyChild(firstParagraph.type.startText.length)
        }

        var index = 0
        richParagraphList.fastForEachIndexed { paragraphIndex, richParagraph ->
            val result = richParagraph.getRichSpanByTextIndex(
                paragraphIndex = paragraphIndex,
                textIndex = textIndex,
                offset = index,
                ignoreCustomFiltering = ignoreCustomFiltering,
            )
            if (result.second != null)
                return result.second
            else
                index = result.first
        }
        return null
    }

    /**
     * Returns a list of [RichSpan]s that contains at least a part of the given [searchTextRange].
     * If no [RichSpan] contains at least a part of the given [searchTextRange], an empty list is returned.
     *
     * @param searchTextRange The [TextRange] to search for.
     * @return A list of [RichSpan]s that contains a part of the given [searchTextRange], or an empty list if no such [RichSpan] exists.
     */
    private fun getRichSpanListByTextRange(searchTextRange: TextRange): List<RichSpan> {
        var index = 0
        val richSpanList = mutableListOf<RichSpan>()
        richParagraphList.fastForEachIndexed { paragraphIndex, richParagraphStyle ->
            val result = richParagraphStyle.getRichSpanListByTextRange(
                paragraphIndex = paragraphIndex,
                searchTextRange = searchTextRange,
                offset = index,
            )
            richSpanList.addAll(result.second)
            index = result.first
        }
        return richSpanList
    }

    /**
     * Internal helper for testing
     */
    internal fun printParagraphs() {
        richParagraphList.fastForEachIndexed { i, richParagraph ->
            println("Paragraph $i: $richParagraph")
        }
    }

    /**
     * Returns a copy of this [RichTextState].
     * It can be used to create a snapshot of the current state.
     *
     * @return A copy of this [RichTextState].
     */
    public fun copy(): RichTextState {
        val richParagraphList = richParagraphList.map { it.copy() }
        val richTextState = RichTextState(richParagraphList)
        richTextState.updateTextFieldValue(textFieldValue)
        richTextState.config.linkColor = config.linkColor
        richTextState.config.linkTextDecoration = config.linkTextDecoration
        richTextState.config.codeSpanColor = config.codeSpanColor
        richTextState.config.codeSpanBackgroundColor = config.codeSpanBackgroundColor
        richTextState.config.codeSpanStrokeColor = config.codeSpanStrokeColor
        richTextState.config.listIndent = config.listIndent
        richTextState.config.orderedListIndent = config.orderedListIndent
        richTextState.config.unorderedListIndent = config.unorderedListIndent
        richTextState.config.preserveStyleOnEmptyLine = config.preserveStyleOnEmptyLine

        return richTextState
    }

    /**
     * Updates the [RichTextState] with the given [text].
     *
     * @param text The text to update the [RichTextState] with.
     */
    public fun setText(text: String): RichTextState {
        val textFieldValue =
            TextFieldValue(
                text = text,
                selection = TextRange(text.length),
            )

        onTextFieldValueChange(
            newTextFieldValue = textFieldValue
        )
        return this
    }

    /**
     * Updates the [RichTextState] with the given [html].
     *
     * @param html The html to update the [RichTextState] with.
     */
    public fun setHtml(html: String): RichTextState {
        val richParagraphList = RichTextStateHtmlParser.encode(html).richParagraphList
        updateRichParagraphList(richParagraphList)
        return this
    }

    /**
     * Updates the [RichTextState] with the given [markdown].
     *
     * @param markdown The markdown to update the [RichTextState] with.
     */
    public fun setMarkdown(markdown: String): RichTextState {
        val richParagraphList = RichTextStateMarkdownParser.encode(markdown).richParagraphList
        updateRichParagraphList(richParagraphList)
        return this
    }

    /**
     * Updates the [RichTextState] with the given [newRichParagraphList].
     * The [RichTextState] will be updated with the given [newRichParagraphList] and the [annotatedString] will be updated.
     *
     * @param newRichParagraphList The [RichParagraph]s to update the [RichTextState] with.
     */
    internal fun updateRichParagraphList(newRichParagraphList: List<RichParagraph>) {
        richParagraphList.clear()
        richParagraphList.addAll(newRichParagraphList)

        if (richParagraphList.isEmpty())
            richParagraphList.add(RichParagraph())

        val newStyledRichSpanList = mutableListOf<RichSpan>()

        usedInlineContentMapKeys.clear()

        annotatedString = buildAnnotatedString {
            var index = 0
            richParagraphList.fastForEachIndexed { i, richParagraph ->
                withStyle(richParagraph.paragraphStyle.merge(richParagraph.type.getStyle(config))) {
                    append(richParagraph.type.startText)
                    val richParagraphStartTextLength = richParagraph.type.startText.length
                    richParagraph.type.startRichSpan.textRange = TextRange(index, index + richParagraphStartTextLength)
                    index += richParagraphStartTextLength
                    withStyle(RichSpanStyle.DefaultSpanStyle) {
                        index = append(
                            state = this@RichTextState,
                            richSpanList = richParagraph.children,
                            startIndex = index,
                            onStyledRichSpan = {
                                newStyledRichSpanList.add(it)
                            },
                        )

                        if (!singleParagraphMode) {
                            if (i != richParagraphList.lastIndex) {
                                append(' ')
                                index++
                            }
                        }
                    }
                }
            }
        }

        inlineContentMap.keys.forEach { key ->
            if (key !in usedInlineContentMapKeys) {
                inlineContentMap.remove(key)
            }
        }

        styledRichSpanList.clear()
        textFieldValue = TextFieldValue(
            text = annotatedString.text,
            selection = TextRange(annotatedString.text.length),
        )
        visualTransformation = VisualTransformation { _ ->
            TransformedText(
                text = annotatedString,
                offsetMapping = OffsetMapping.Identity
            )
        }
        styledRichSpanList.addAll(newStyledRichSpanList)

        // Update current span style
        updateCurrentSpanStyle()

        // Update current paragraph style
        updateCurrentParagraphStyle()

        // Check paragraphs type
        checkParagraphsType()
    }

    private fun checkParagraphsType() {
        var orderedListNumber = 0
        var orderedListStartTextSpanStyle = SpanStyle()
        richParagraphList.forEach { richParagraph ->
            val type = richParagraph.type
            if (type is OrderedList) {
                orderedListNumber++

                if (orderedListNumber == 1)
                    orderedListStartTextSpanStyle = richParagraph.getFirstNonEmptyChild()?.spanStyle ?: SpanStyle()

                type.number = orderedListNumber
                type.startTextSpanStyle = orderedListStartTextSpanStyle
            } else {
                orderedListNumber = 0
                orderedListStartTextSpanStyle = SpanStyle()
            }
        }
    }

    /**
     * Returns the [RichTextState] as a text string.
     *
     * @return The text string.
     */
    public fun toText(): String =
        toText(richParagraphList = richParagraphList)

    /**
     * Decodes the [RichTextState] to a html string.
     *
     * @return The html string.
     */
    public fun toHtml(): String {
        return RichTextStateHtmlParser.decode(this)
    }

    /**
     * Decodes the [RichTextState] to a markdown string.
     *
     * @return The html string.
     */
    public fun toMarkdown(): String {
        return RichTextStateMarkdownParser.decode(this)
    }

    /**
     * Clears the [RichTextState] and sets the [TextFieldValue] to an empty value.
     */
    public fun clear() {
        richParagraphList.clear()
        richParagraphList.add(RichParagraph())
        updateTextFieldValue(TextFieldValue())
    }

    public companion object {
        public val Saver: Saver<RichTextState, *> = listSaver(
            save = {
                listOf(
                    it.toHtml(),
                    it.selection.start.toString(),
                    it.selection.end.toString(),
                )
            },
            restore = {
                val html = it[0]
                val selectionStart = it[1].toInt()
                val selectionEnd = it[2].toInt()
                val selection = TextRange(selectionStart, selectionEnd)
                val richTextState = RichTextState()
                richTextState.setHtml(html)
                richTextState.updateTextFieldValue(
                    richTextState.textFieldValue.copy(
                        selection = selection
                    )
                )
                richTextState
            }
        )
    }
}

private fun SpanStyle.isUnspecifiedColorSpan(): Boolean {
    return this == SpanStyle(color = Color.Unspecified)
}
