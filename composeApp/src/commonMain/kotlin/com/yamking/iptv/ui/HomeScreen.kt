package com.yamking.iptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import com.yamking.iptv.data.TvListFormat
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.yamking.iptv.AppState
import com.yamking.iptv.PlatformBackHandler
import com.yamking.iptv.ScanProgress
import com.yamking.iptv.countryName
import com.yamking.iptv.data.Channel
import com.yamking.iptv.rememberPlaylistFilePicker
import com.yamking.iptv.rememberPlaylistFileSaver
import kotlinx.coroutines.launch

private const val ALL = "Toutes"
private const val FAVORITES = "Favoris"
private const val RECENTS = "Récents"
private const val UNKNOWN = "?"

enum class SortMode(val label: String) {
    Added("Ordre d'ajout"),
    Newest("Ajouts récents"),
    Name("Nom"),
    Quality("Qualité"),
}

/**
 * État de l'écran d'accueil, conservé par [App] : recherche, filtres, tri et position
 * de défilement survivent ainsi à l'ouverture puis à la fermeture du lecteur.
 */
class HomeUiState {
    /** Réglages de la recherche automatique, retrouvés d'une recherche à l'autre. */
    val blindForm = BlindScanForm()
    var query by mutableStateOf("")
    var category by mutableStateOf(ALL)

    /** Code pays, [UNKNOWN] pour les chaînes sans pays, null pour tous. */
    var country by mutableStateOf<String?>(null)
    var language by mutableStateOf<String?>(null)
    var sort by mutableStateOf(SortMode.Added)

    /** La proposition « Reprendre la dernière chaîne » a été utilisée ou fermée. */
    var resumeDismissed by mutableStateOf(false)
    val grid = LazyGridState()
    val categoryList = LazyListState()
}

@Composable
fun HomeScreen(state: AppState, ui: HomeUiState, onPlay: (Channel, List<Channel>) -> Unit) {
    val scope = rememberCoroutineScope()
    // Un changement de filtre repart du haut de la grille ; un simple retour du lecteur, non.
    fun filterChanged(change: () -> Unit) {
        change()
        scope.launch { ui.grid.scrollToItem(0) }
    }

    var importing by remember { mutableStateOf(false) }
    // Rouverte d'office si une recherche tourne encore ou attend la validation de ses résultats.
    var searching by remember { mutableStateOf(state.blindProgress != null || state.blindResults.isNotEmpty() || state.blindSources.isNotEmpty()) }
    if (searching) BlindScanDialog(state, ui.blindForm, onDismiss = { searching = false })
    val onSearch = { searching = true }
    var editing by remember { mutableStateOf<Channel?>(null) }
    val onImport = {
        state.error = null
        importing = true
    }
    // La fenêtre d'import se ferme dès que la vérification démarre ; elle reste ouverte en cas d'erreur de chargement.
    if (state.scan != null) importing = false
    if (importing) {
        Dialog(onDismissRequest = { if (!state.loading) importing = false }) {
            ImportForm(
                state = state,
                title = "Ajouter une playlist",
                message = "Les chaînes qui répondent s'ajoutent à votre bibliothèque. Celles déjà présentes ne sont pas touchées.",
                onCancel = { importing = false },
            )
        }
    }
    editing?.let { channel ->
        EditDialog(channel, onDismiss = { editing = null }) { name, groups ->
            state.edit(channel, name, groups)
            editing = null
        }
    }

    val channels = state.channels
    val favorites = state.favorites
    val recents = state.recents
    val byUrl = remember(channels) { channels.associateBy { it.url } }

    // Catégories avec leur nombre de chaînes, les plus fournies d'abord.
    val categories = remember(channels, favorites, recents) {
        val counts = channels.flatMap { it.groups }.groupingBy { it }.eachCount()
        listOf(
            ALL to channels.size,
            FAVORITES to favorites.count { it in byUrl },
            RECENTS to recents.count { it in byUrl },
        ) + counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    if (categories.none { it.first == ui.category }) ui.category = ALL

    // Pays et langues présents dans la bibliothèque, avec leur nombre de chaînes.
    val countries = remember(channels) {
        channels.groupingBy { it.country ?: UNKNOWN }.eachCount().entries
            .sortedWith(compareBy({ it.key == UNKNOWN }, { -it.value }))
            .map { Triple(it.key, if (it.key == UNKNOWN) "Pays inconnu" else countryName(it.key), it.value) }
    }
    val languages = remember(channels) {
        channels.mapNotNull { it.language }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key to it.value }
    }
    if (ui.country != null && countries.none { it.first == ui.country }) ui.country = null
    if (ui.language != null && languages.none { it.first == ui.language }) ui.language = null

    val query = ui.query
    val visible = remember(channels, favorites, recents, query, ui.category, ui.country, ui.language, ui.sort) {
        val base = when (ui.category) {
            ALL -> channels
            FAVORITES -> favorites.mapNotNull { byUrl[it] }
            RECENTS -> recents.mapNotNull { byUrl[it] }
            else -> channels.filter { ui.category in it.groups }
        }
        val needle = query.trim()
        val filtered = base.filter { channel ->
            (ui.country == null || (channel.country ?: UNKNOWN) == ui.country) &&
                (ui.language == null || channel.language == ui.language) &&
                (needle.isEmpty() ||
                    channel.name.contains(needle, ignoreCase = true) ||
                    channel.groups.any { it.contains(needle, ignoreCase = true) } ||
                    channel.country?.let { countryName(it).contains(needle, ignoreCase = true) } == true)
        }
        when (ui.sort) {
            SortMode.Added -> filtered
            SortMode.Newest -> if (ui.category == FAVORITES || ui.category == RECENTS) filtered else filtered.reversed()
            SortMode.Name -> filtered.sortedBy { it.name.lowercase() }
            SortMode.Quality -> filtered.sortedByDescending { it.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0 }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val closeDrawer = { scope.launch { drawer.close() } }

        // Écran étroit : les catégories vivent dans un tiroir ouvert par le bouton menu ou un glissement.
        // Écran large : le même panneau reste affiché en permanence à gauche.
        val body: @Composable () -> Unit = {
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    SidePanel(state, ui, categories, countries, languages, ::filterChanged, onImport, onSearch, onAction = {})
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(
                        state = state,
                        ui = ui,
                        shown = visible.size,
                        onQuery = { value -> filterChanged { ui.query = value } },
                        title = if (wide) null else if (ui.category == ALL) "IPTV Player" else ui.category,
                        onMenu = { scope.launch { drawer.open() } },
                    )
                    if (!importing) {
                        state.error?.let { Notice(it, Palette.Danger) { state.error = null } }
                        state.info?.let { Notice(it, Palette.Cyan) { state.info = null } }
                    }
                    state.lastDeleted?.let { removed ->
                        ActionBanner(
                            text = "« ${removed.name} » supprimée.",
                            action = "Annuler",
                            onAction = state::undoDelete,
                            onDismiss = state::forgetDeleted,
                        )
                    }
                    AnimatedVisibility(state.scan != null) {
                        state.scan?.let { ScanBanner(it, onCancel = state::cancelScan) }
                    }
                    if (state.scan == null && state.pending.isNotEmpty()) {
                        ActionBanner(
                            text = "Import interrompu : ${state.pending.size} chaînes restent à vérifier.",
                            action = "Reprendre",
                            onAction = state::resumePending,
                            onDismiss = state::dismissPending,
                        )
                    }
                    val last = state.lastWatched
                    if (last != null && !ui.resumeDismissed) {
                        ActionBanner(
                            text = "Dernière chaîne regardée : ${last.name}",
                            action = "Reprendre",
                            onAction = {
                                ui.resumeDismissed = true
                                onPlay(last, visible.ifEmpty { channels })
                            },
                            onDismiss = { ui.resumeDismissed = true },
                        )
                    }
                    if (visible.isEmpty()) {
                        EmptyState(scanning = state.scan != null, category = ui.category)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (wide) 190.dp else 150.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            state = ui.grid,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(visible, key = { it.url }) { channel ->
                                ChannelCard(
                                    channel = channel,
                                    isFavorite = channel.url in favorites,
                                    reorderable = ui.category == FAVORITES && ui.sort == SortMode.Added,
                                    onFavorite = { state.toggleFavorite(channel) },
                                    onClick = {
                                        ui.resumeDismissed = true
                                        onPlay(channel, visible)
                                    },
                                    onEdit = { editing = channel },
                                    onMove = { delta -> state.moveFavorite(channel, delta) },
                                    onDelete = { state.delete(channel) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (wide) {
            body()
        } else {
            PlatformBackHandler(enabled = drawer.isOpen) { closeDrawer() }
            ModalNavigationDrawer(
                drawerState = drawer,
                scrimColor = Color(0x99000000),
                drawerContent = {
                    SidePanel(
                        state, ui, categories, countries, languages,
                        onFilter = { change -> filterChanged(change); closeDrawer() },
                        onImport = { closeDrawer(); onImport() },
                        onSearch = { closeDrawer(); onSearch() },
                        onAction = { closeDrawer() },
                        width = 290.dp,
                    )
                },
                content = body,
            )
        }
    }
}

// ---- Panneau latéral : filtres, catégories, réglages --------------------------------------------

@Composable
private fun SidePanel(
    state: AppState,
    ui: HomeUiState,
    categories: List<Pair<String, Int>>,
    countries: List<Triple<String, String, Int>>,
    languages: List<Pair<String, Int>>,
    onFilter: (change: () -> Unit) -> Unit,
    onImport: () -> Unit,
    onSearch: () -> Unit,
    onAction: () -> Unit,
    width: Dp = 230.dp,
) {
    val saveFile = rememberPlaylistFileSaver()
    val pickBackup = rememberPlaylistFilePicker { _, content -> state.importFromText(content) }
    var confirmClear by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    if (exporting) {
        ExportDialog(state, onDismiss = { exporting = false }) { categories, favoritesOnly ->
            exporting = false
            saveFile("mes-chaines.${TvListFormat.EXTENSION}", state.exportTvList(categories, favoritesOnly))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Vider la bibliothèque ?") },
            text = { Text("Toutes les chaînes, les favoris et l'historique seront supprimés de cet appareil.") },
            confirmButton = {
                TextButton(onClick = {
                    state.clearLibrary()
                    confirmClear = false
                }) { Text("Tout supprimer", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }

    Column(Modifier.width(width).fillMaxHeight().background(Palette.Surface).padding(vertical = 20.dp)) {
        Brand(Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(14.dp))

        // Filtres pays et langue, combinables avec la catégorie et la recherche.
        if (countries.size > 1) {
            FilterMenu(
                label = "Pays",
                current = countries.firstOrNull { it.first == ui.country }?.second ?: "Tous",
                options = listOf(Triple<String?, String, Int?>(null, "Tous les pays", null)) +
                    countries.map { Triple<String?, String, Int?>(it.first, it.second, it.third) },
                selected = ui.country,
                onSelect = { value -> onFilter { ui.country = value } },
            )
        }
        if (languages.isNotEmpty()) {
            FilterMenu(
                label = "Langue",
                current = ui.language ?: "Toutes",
                options = listOf(Triple<String?, String, Int?>(null, "Toutes les langues", null)) +
                    languages.map { Triple<String?, String, Int?>(it.first, it.first, it.second) },
                selected = ui.language,
                onSelect = { value -> onFilter { ui.language = value } },
            )
        }

        Text(
            "CATÉGORIES",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextMuted,
            modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 6.dp),
        )
        LazyColumn(Modifier.weight(1f), state = ui.categoryList, contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(categories) { (name, count) ->
                val active = name == ui.category
                Row(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (active) Palette.AccentSoft else Color.Transparent)
                        .focusHighlight(MaterialTheme.shapes.small)
                        .clickable { onFilter { ui.category = name } }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        color = if (active) Palette.Text else Palette.TextMuted,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        count.toString(),
                        color = if (active) Palette.Text else Palette.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // Qualité par défaut : chaque chaîne démarre à cette qualité (ou la plus proche en dessous),
        // sauf choix différent fait dans le lecteur.
        FilterMenu(
            label = "Qualité par défaut",
            current = state.maxHeight?.let { "${it}p" } ?: "Auto",
            options = listOf(null, 1080, 720, 480, 360).map { value ->
                Triple<String?, String, Int?>(value?.toString(), value?.let { "${it}p" } ?: "Automatique", null)
            },
            selected = state.maxHeight?.toString(),
            onSelect = { value -> state.updateMaxHeight(value?.toInt()) },
        )
        TextButton(onClick = onImport, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Ajouter une playlist")
        }
        TextButton(onClick = onSearch, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Rechercher des chaînes")
        }
        Box(Modifier.padding(horizontal = 12.dp)) {
            var more by remember { mutableStateOf(false) }
            TextButton(onClick = { more = true }) { Text("Exporter, sauvegarder…", color = Palette.TextMuted) }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(
                    text = { Text("Exporter par catégories (.tvlist)") },
                    onClick = {
                        more = false
                        onAction()
                        exporting = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Exporter tout en M3U") },
                    onClick = {
                        more = false
                        onAction()
                        saveFile("mes-chaines.m3u", state.exportM3u())
                    },
                )
                DropdownMenuItem(
                    text = { Text("Sauvegarde complète") },
                    onClick = {
                        more = false
                        onAction()
                        saveFile("iptv-sauvegarde.json", state.backupJson())
                    },
                )
                DropdownMenuItem(
                    text = { Text("Restaurer une sauvegarde") },
                    onClick = {
                        more = false
                        onAction()
                        pickBackup()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Vider la bibliothèque", color = Palette.Danger) },
                    onClick = {
                        more = false
                        confirmClear = true
                    },
                )
            }
        }
    }
}

/** Ligne « Libellé : valeur » qui ouvre une liste de choix (valeur, texte, nombre de chaînes). */
@Composable
private fun FilterMenu(
    label: String,
    current: String,
    options: List<Triple<String?, String, Int?>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Box(Modifier.padding(horizontal = 12.dp)) {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }) {
            Text("$label : ", color = Palette.TextMuted)
            Text(current, color = if (selected != null) Palette.Accent else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text, count) ->
                DropdownMenuItem(
                    text = { Text(text, color = if (value == selected) Palette.Accent else Palette.Text) },
                    leadingIcon = { SelectionMark(value == selected) },
                    trailingIcon = { count?.let { Text(it.toString(), color = Palette.TextMuted, style = MaterialTheme.typography.labelSmall) } },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(12.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Palette.Accent, Palette.Cyan)))
        )
        Spacer(Modifier.width(10.dp))
        Text("IPTV Player", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

// ---- Barre du haut et bandeaux ------------------------------------------------------------------

@Composable
private fun TopBar(
    state: AppState,
    ui: HomeUiState,
    shown: Int,
    onQuery: (String) -> Unit,
    title: String?,
    onMenu: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = if (title != null) 4.dp else 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
        if (title != null) {
            // Écran étroit : bouton menu qui ouvre le tiroir des catégories, suivi de la catégorie affichée.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.Menu, "Ouvrir les catégories")
                }
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$shown chaînes",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextMuted,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.padding(start = if (title != null) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(ui.query, onQuery, Modifier.weight(1f))
            if (title == null) {
                Spacer(Modifier.width(10.dp))
                Text("$shown chaînes", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
            }
            Box {
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = true }) {
                    // Le tri actif est écrit en toutes lettres, pas seulement signalé par la couleur.
                    Text(
                        if (ui.sort == SortMode.Added) "Tri" else "Tri : ${ui.sort.label}",
                        color = if (ui.sort == SortMode.Added) Palette.TextMuted else Palette.Accent,
                        maxLines = 1,
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label, color = if (mode == ui.sort) Palette.Accent else Palette.Text) },
                            leadingIcon = { SelectionMark(mode == ui.sort) },
                            onClick = {
                                open = false
                                ui.sort = mode
                            },
                        )
                    }
                }
            }
            IconButton(onClick = state::rescan, enabled = state.scan == null) {
                Icon(Icons.Filled.Refresh, "Revérifier les chaînes", tint = Palette.TextMuted)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier.heightIn(min = 44.dp) // grandit avec la taille de texte du système
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.SurfaceHigh)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = Palette.TextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Nom, catégorie ou pays", color = Palette.TextMuted, fontSize = 14.sp, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            // Zone d'appui de 40 dp autour d'une icône de 18 dp.
            IconButton(onClick = { onQuery("") }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, "Effacer la recherche", tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Notice(message: String, color: Color, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

@Composable
private fun ActionBanner(text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, MaterialTheme.shapes.medium)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) { Text(action) }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, "Fermer", tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ScanBanner(progress: ScanProgress, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, MaterialTheme.shapes.medium)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Vérification des chaînes",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${progress.done} / ${progress.total} testées · ${progress.valid} valides",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextMuted,
                )
            }
            TextButton(onClick = onCancel) { Text("Arrêter") }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
            color = Palette.Accent,
            trackColor = Palette.SurfaceHigh,
            modifier = Modifier.fillMaxWidth().padding(end = 10.dp).height(6.dp).clip(CircleShape),
        )
    }
}

// ---- Carte d'une chaîne -------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(
    channel: Channel,
    isFavorite: Boolean,
    reorderable: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.clip(MaterialTheme.shapes.medium)
            .background(Palette.Surface)
            .focusHighlight(MaterialTheme.shapes.medium)
            // Appui long (ou clic maintenu) : même menu que le bouton « ⋮ ».
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                .background(Brush.linearGradient(listOf(Palette.SurfaceHigh, Color(0xFF121624)))),
            contentAlignment = Alignment.Center,
        ) {
            // Les initiales restent visibles derrière le logo s'il est absent ou ne charge pas.
            Text(
                initials(channel.name),
                color = Palette.OutlineStrong,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
            )
            if (channel.logo != null) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .background(Brush.linearGradient(listOf(Palette.SurfaceHigh, Color(0xFF121624))))
                        .padding(18.dp),
                )
            }
            channel.quality?.let {
                Text(
                    it,
                    color = Palette.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC0B0D14))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd).size(44.dp)) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = if (isFavorite) Palette.Heart else Palette.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    channel.name,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (listOfNotNull(channel.country) + channel.groups).joinToString(" · ").ifEmpty { "Sans catégorie" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.MoreVert, "Options de la chaîne", tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Renommer, catégories") }, onClick = { menu = false; onEdit() })
                    if (reorderable) {
                        DropdownMenuItem(text = { Text("Monter dans les favoris") }, onClick = { menu = false; onMove(-1) })
                        DropdownMenuItem(text = { Text("Descendre dans les favoris") }, onClick = { menu = false; onMove(1) })
                    }
                    DropdownMenuItem(
                        text = { Text("Supprimer", color = Palette.Danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditDialog(channel: Channel, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var name by remember(channel.url) { mutableStateOf(channel.name) }
    var groups by remember(channel.url) { mutableStateOf(channel.groups.joinToString("; ")) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 460.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Palette.Surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Modifier la chaîne", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = groups,
                onValueChange = { groups = it },
                label = { Text("Catégories (séparées par ;)") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Annuler", color = Palette.TextMuted) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(name, groups.split(';')) },
                    enabled = name.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Enregistrer") }
            }
        }
    }
}

/** Choix des catégories à mettre dans un fichier « .tvlist ». */
@Composable
internal fun ExportDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onExport: (categories: Set<String>, favoritesOnly: Boolean) -> Unit,
) {
    val categories = remember { state.exportCategories() }
    var selected by remember { mutableStateOf(categories.map { it.first }.toSet()) }
    var favoritesOnly by remember { mutableStateOf(false) }
    val count = state.exportSelection(selected, favoritesOnly).size
    val allSelected = selected.size == categories.size

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 460.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Palette.Surface)
                .padding(top = 24.dp, bottom = 16.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Exporter par catégories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Le fichier .tvlist garde toutes les informations des chaînes et se réimporte dans l'application.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            CheckRow(
                label = if (allSelected) "Tout désélectionner" else "Tout sélectionner",
                checked = allSelected,
                emphasized = true,
            ) { selected = if (allSelected) emptySet() else categories.map { it.first }.toSet() }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(categories) { (name, size) ->
                    CheckRow(label = name, checked = name in selected, count = size) {
                        selected = if (name in selected) selected - name else selected + name
                    }
                }
            }
            CheckRow(label = "Favoris seulement", checked = favoritesOnly, emphasized = true) { favoritesOnly = !favoritesOnly }
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (count == 0) "Aucune chaîne sélectionnée" else "$count chaînes",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Annuler", color = Palette.TextMuted) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onExport(selected, favoritesOnly) },
                    enabled = count > 0,
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Exporter") }
            }
        }
    }
}

/** Ligne à cocher : toute la ligne est cliquable, pas seulement la case. */
@Composable
internal fun CheckRow(
    label: String,
    checked: Boolean,
    count: Int? = null,
    emphasized: Boolean = false,
    detail: String = "",
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .focusHighlight(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            label,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detail.isNotEmpty()) Text(detail, color = Palette.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        count?.let { Text(it.toString(), color = Palette.TextMuted, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun EmptyState(scanning: Boolean, category: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            when {
                scanning -> "Recherche des chaînes disponibles…"
                category == FAVORITES -> "Aucun favori pour l'instant. Touchez le cœur d'une chaîne pour l'ajouter."
                category == RECENTS -> "Aucune chaîne regardée pour l'instant."
                else -> "Aucune chaîne ne correspond."
            },
            color = Palette.TextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Coche de l'option choisie dans un menu : l'état ne repose pas sur la seule couleur. */
@Composable
private fun SelectionMark(selected: Boolean) {
    if (selected) {
        Icon(Icons.Filled.Check, "Sélectionné", tint = Palette.Accent, modifier = Modifier.size(18.dp))
    } else {
        Spacer(Modifier.size(18.dp))
    }
}

private fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
