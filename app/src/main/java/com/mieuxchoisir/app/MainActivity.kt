package com.mieuxchoisir.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import java.util.Locale

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

sealed interface UiState {
    data object Idle : UiState
    data class Loading(val message: String) : UiState
    data class Error(val message: String) : UiState
    data class Done(val rec: Recommendation) : UiState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green,
                    secondary = Color(0xFF558B2F),
                )
            ) {
                AppScreen(onScan = ::scanBarcode)
            }
        }
    }

    private fun scanBarcode(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            )
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { b -> b.rawValue?.let(onResult) }
            .addOnFailureListener { e ->
                onError("Scanner indisponible (${e.localizedMessage}). Saisissez le code à la main.")
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(onScan: (onResult: (String) -> Unit, onError: (String) -> Unit) -> Unit) {
    var barcode by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<UiState>(UiState.Idle) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun search(code: String) {
        val clean = code.filter { it.isDigit() }
        if (clean.length < 8) {
            state = UiState.Error("Code-barres invalide : 8 à 14 chiffres attendus.")
            return
        }
        focus.clearFocus()
        state = UiState.Loading("Lecture du produit…")
        scope.launch {
            state = try {
                val ref = OffApi.getProduct(clean)
                state = UiState.Loading("Produit trouvé : ${ref.name}")
                UiState.Done(Recommender.recommend(ref) { msg -> state = UiState.Loading(msg) })
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Erreur réseau. Vérifiez votre connexion.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mieux Choisir", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Green, titleContentColor = Color.White
                ),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Scannez ou saisissez un code-barres : l'appli propose 5 alternatives " +
                        "moins caloriques, moins sucrées, moins grasses et plus protéinées.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Button(
                    onClick = {
                        onScan(
                            { code -> barcode = code; search(code) },
                            { err -> state = UiState.Error(err) },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = state !is UiState.Loading,
                ) { Text("📷  Scanner un code-barres", fontSize = 17.sp) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = barcode,
                        onValueChange = { barcode = it.filter(Char::isDigit).take(14) },
                        label = { Text("Code-barres (EAN)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number, imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = { search(barcode) }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { search(barcode) },
                        enabled = state !is UiState.Loading,
                    ) { Text("OK") }
                }
            }

            when (val s = state) {
                UiState.Idle -> item {
                    Text(
                        "Exemple : 3017620422003",
                        color = Color.Gray,
                        modifier = Modifier.clickable { barcode = "3017620422003"; search(barcode) },
                    )
                }
                is UiState.Loading -> item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(s.message)
                    }
                }
                is UiState.Error -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                        Text(s.message, Modifier.padding(16.dp), color = Red)
                    }
                }
                is UiState.Done -> {
                    val rec = s.rec
                    item { SectionTitle("Produit scanné") }
                    item { ProductCard(rec.reference, reference = null, rank = null, retailers = Recommender.retailersOf(rec.reference)) }
                    item {
                        SectionTitle("Top ${rec.alternatives.size} alternatives")
                        Text(
                            "Catégorie : ${rec.categoryLabel} • ${rec.availability.label}",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        )
                    }
                    if (rec.alternatives.isEmpty()) {
                        item {
                            Text("Aucune alternative nettement meilleure trouvée dans cette catégorie. Ce produit est déjà un bon choix !")
                        }
                    }
                    items(rec.alternatives.size) { i ->
                        val alt = rec.alternatives[i]
                        ProductCard(alt.product, reference = rec.reference.nutrition, rank = i + 1, retailers = alt.retailers)
                    }
                    item {
                        Text(
                            "Valeurs pour 100 g/100 ml. Données : Open Food Facts (contributif, ODbL). " +
                                "La disponibilité en magasin peut varier.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun ProductCard(p: Product, reference: Nutrition?, rank: Int?, retailers: List<String>) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://fr.openfoodfacts.org/produit/${p.code}"))
            )
        },
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rank != null) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(Green),
                        contentAlignment = Alignment.Center,
                    ) { Text("$rank", color = Color.White, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                }
                AsyncImage(
                    model = p.imageUrl,
                    contentDescription = p.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF1F1F1)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (p.brand.isNotBlank()) Text(p.brand, color = Color.Gray, fontSize = 13.sp)
                }
                p.nutriscore?.let { NutriScoreBadge(it) }
            }
            Spacer(Modifier.height(10.dp))
            val n = p.nutrition
            if (n == null) {
                Text("Valeurs nutritionnelles incomplètes", color = Color.Gray)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Calories", n.kcal, "kcal", reference?.kcal, lowerIsBetter = true)
                    Metric("Sucres", n.sugars, "g", reference?.sugars, lowerIsBetter = true)
                    Metric("Lipides", n.fat, "g", reference?.fat, lowerIsBetter = true)
                    Metric("Protéines", n.proteins, "g", reference?.proteins, lowerIsBetter = false)
                }
            }
            if (retailers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("🛒 " + retailers.take(5).joinToString(" · "), fontSize = 12.sp, color = Color(0xFF455A64))
            } else if (p.stores.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("🛒 " + p.stores, fontSize = 12.sp, color = Color(0xFF455A64), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: Double, unit: String, ref: Double?, lowerIsBetter: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Color.Gray)
        Text("${fmt(value)} $unit", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (ref != null) {
            val diff = value - ref
            val good = if (lowerIsBetter) diff < 0 else diff > 0
            val color = when {
                kotlin.math.abs(diff) < 0.05 -> Color.Gray
                good -> Green
                else -> Red
            }
            Text((if (diff > 0) "+" else "") + fmt(diff), fontSize = 12.sp, color = color)
        }
    }
}

@Composable
private fun NutriScoreBadge(grade: String) {
    val color = when (grade.lowercase()) {
        "a" -> Color(0xFF038141); "b" -> Color(0xFF85BB2F); "c" -> Color(0xFFFECB02)
        "d" -> Color(0xFFEE8100); "e" -> Color(0xFFE63E11); else -> Color.Gray
    }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 4.dp)
    ) { Text("Nutri ${grade.uppercase()}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

private fun fmt(v: Double): String =
    if (kotlin.math.abs(v) >= 100) String.format(Locale.FRANCE, "%.0f", v)
    else String.format(Locale.FRANCE, "%.1f", v)
