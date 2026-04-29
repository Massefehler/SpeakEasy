package com.speakeasy.intercom

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView, deren intrinsische Drawable-Größe NICHT in die Parent-`wrap_content`-
 * Messung einfließt — meldet 0 als Suggested-Minimum.
 *
 * Hintergrund: Eine reguläre `ImageView` mit `layout_height="match_parent"` in
 * einem `wrap_content`-Container, der wiederum in einem `NestedScrollView`
 * (MeasureSpec UNSPECIFIED) liegt, gibt ihre intrinsische Drawable-Höhe als
 * Mindestgröße zurück. Bei einem 1060-px-Bild (14:10) wird der Header dadurch
 * 1060 px hoch — auf einer 1080 px breiten View entsteht ein nahezu
 * quadratisches (1:1) Erscheinungsbild statt des gewünschten Banner-Looks.
 *
 * Diese Variante meldet 0 als Suggested-Minimum, sodass die Höhe ausschließlich
 * vom umgebenden Layout vorgegeben wird (typischerweise `match_parent` zum
 * Header-Container, dessen Höhe das Geschwister `headerContent` bestimmt).
 * `scaleType="matrix"` mit eigener Skalierung füllt den so entstandenen
 * Banner-Bereich passend.
 */
class BackgroundImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    override fun getSuggestedMinimumWidth(): Int = 0
    override fun getSuggestedMinimumHeight(): Int = 0
}
