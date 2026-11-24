package es.bicilab.bicicam.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Azul marino de la equipación BCLB 2027. */
val Navy = Color(0xFF1F2555)

/** Rojo del piloto de grabación. */
val RecordingRed = Color(0xFFD7261E)

/** Gris de BiciLAB, para lo secundario. */
val BiciLabGrey = Color(0xFF61737F)

/**
 * Fondo blanco y azul marino: el Karoo se mira al sol y con prisa, y sobre blanco se lee mejor.
 * Los botones son casi rectos, como los campos de las páginas de datos, y altos para tocarlos con guantes.
 */
private val Colors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = Navy,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF14183A),
    surface = Color.White,
    onSurface = Color(0xFF14183A),
    onSurfaceVariant = BiciLabGrey,
    outline = Navy,
    outlineVariant = Color(0xFFC9D0D6),
    error = RecordingRed,
)

/**
 * La letra es la del sistema (Roboto), y es a propósito: es la del propio Karoo y la única que admiten los
 * campos de datos, que se pintan con vistas remotas de Android. Con otra letra, la app no casaría ni con
 * sus campos ni con el aparato en el que vive. Los titulares van en negrita, como el valor de los campos.
 */
private val Type = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    )
}

private val ButtonShape = RoundedCornerShape(2.dp)
private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
}

/** La acción principal de la pantalla. */
@Composable
fun MainButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick, modifier.heightIn(min = 52.dp), enabled, ButtonShape, colors, contentPadding = ButtonPadding, content = content)
}

/** Una acción que no es la principal. */
@Composable
fun PlainButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(
        onClick,
        modifier.heightIn(min = 52.dp),
        shape = ButtonShape,
        border = BorderStroke(1.5.dp, Navy),
        contentPadding = ButtonPadding,
        content = content,
    )
}

/** Un enlace a otra pantalla de la app. */
@Composable
fun LinkButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), shape = ButtonShape, content = content)
}
