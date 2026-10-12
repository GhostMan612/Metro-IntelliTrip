package com.intellitrip.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * The four primary destinations from the recovery plan, section 5.1.
 *
 * A single flat enum is deliberate. The audit warns against a destination model chosen
 * by ideology rather than by task, so this is the smallest set that covers the rider
 * flows in 5.3 without forcing one of them into a menu: everything else, including
 * scope and diagnostics, nests under More.
 */
enum class Destination(
    val label: String,
    val icon: ImageVector,
    /** Spoken name. Read separately from [label] so a screen reader is not read "Plan Plan". */
    val spoken: String,
) {
    Explore("Explore", Icons.Filled.Explore, "Explore"),
    Plan("Plan", Icons.Filled.Route, "Plan a trip"),
    Saved("Saved", Icons.Filled.Bookmark, "Saved"),
    More("More", Icons.Filled.MoreHoriz, "More"),
}

/**
 * Compact-width navigation: a bottom bar.
 *
 * Every item carries an explicit content description that names the action, not the
 * label, so TalkBack announces "Plan a trip" rather than repeating the visible word.
 */
@Composable
fun RiderNavigationBar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    showDownloadBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        Destination.entries.forEach { destination ->
            val selected = destination == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    val icon: @Composable () -> Unit = {
                        BadgedBox(
                            badge = {
                                // Only Explore carries a badge, and only to say the
                                // schedule is missing. A badge that can appear on
                                // every tab stops meaning anything.
                                if (destination == Destination.Explore && showDownloadBadge) {
                                    Badge()
                                }
                            },
                        ) {
                            Icon(destination.icon, contentDescription = null)
                        }
                    }
                    icon()
                },
                label = { Text(destination.label) },
                modifier = Modifier.semantics {
                    contentDescription = destination.spoken
                },
            )
        }
    }
}

/**
 * Expanded-width navigation: a rail.
 *
 * The plan calls for a rail on tablets. It is selected by the caller from measured
 * width rather than a device type, so a foldable or a rotated phone is handled by the
 * same rule as a tablet.
 */
@Composable
fun RiderNavigationRail(
    current: Destination,
    onSelect: (Destination) -> Unit,
    showDownloadBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier = modifier) {
        Destination.entries.forEach { destination ->
            val selected = destination == current
            NavigationRailItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (destination == Destination.Explore && showDownloadBadge) {
                                Badge()
                            }
                        },
                    ) {
                        Icon(destination.icon, contentDescription = null)
                    }
                },
                label = { Text(destination.label) },
                modifier = Modifier.semantics {
                    contentDescription = destination.spoken
                },
            )
        }
    }
}