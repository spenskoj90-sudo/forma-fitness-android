package ru.forma.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Original FORMA volumetric glass vocabulary: no external blur assets or
 * paid shaders. Static light/reflection, depth cues, guarded transparency.
 * Simplified/high-contrast rendering remains fully usable without effects.
 */
internal data class FormaVisualOptions(
 val highContrast:Boolean=false,
 val reducedMotion:Boolean=false,
 val simplifiedEffects:Boolean=false
)
internal val LocalFormaVisualOptions=staticCompositionLocalOf{FormaVisualOptions()}

@Composable
internal fun FormaGlassPanel(title:String,content:@Composable ColumnScope.()->Unit) {
 val options=LocalFormaVisualOptions.current
 val scheme=MaterialTheme.colorScheme
 val solid=options.highContrast || options.simplifiedEffects
 val radius=RoundedCornerShape(20.dp)
 val base=scheme.surface
 val shine=Color.White.copy(alpha=0.105f)
 val borderColor=when{
  options.highContrast -> scheme.onSurface.copy(alpha=0.83f)
  options.simplifiedEffects -> scheme.outline.copy(alpha=0.65f)
  else -> scheme.outline.copy(alpha=0.35f)
 }
 val background=if(solid)Brush.linearGradient(listOf(base,base))
 else Brush.linearGradient(listOf(
  scheme.surfaceVariant.copy(alpha=0.93f),
  base.copy(alpha=0.88f),
  base.copy(alpha=0.96f)
 ))
 Column(
  modifier=Modifier.fillMaxWidth()
   .clip(radius)
   .background(background)
   .border(width=if(options.highContrast)2.dp else 1.dp,color=borderColor,shape=radius)
   .drawWithContent {
    if(!solid) {
     drawRect(brush=Brush.radialGradient(
      colors=listOf(shine,Color.Transparent),
      center=Offset(size.width*0.14f,size.height*0.03f),
      radius=size.width.coerceAtLeast(1f)*0.86f
     ))
     drawLine(
      color=Color.White.copy(alpha=0.16f),
      start=Offset(20f,1f),
      end=Offset((size.width-20f).coerceAtLeast(20f),1f),
      strokeWidth=1.25.dp.toPx()
     )
    }
    drawContent()
   }
   .padding(18.dp),
  verticalArrangement=Arrangement.spacedBy(10.dp)
 ){
  Text(title,modifier=Modifier.semantics{heading()},
   style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
  content()
 }
}
