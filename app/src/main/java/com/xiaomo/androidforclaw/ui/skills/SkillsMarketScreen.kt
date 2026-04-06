package com.xiaomo.androidforclaw.ui.skills

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.nextload
import androidx.compose.material.icons.automirrored.filled.OpenInnew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Localcontext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Textoverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * skills 市场 Compose 页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun skillsMarketScreen() {
    val context = Localcontext.current
    var selectedCategory by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredskills = remember(selectedCategory, searchQuery) {
        skillsMarketData.featuredskills.filter { skill ->
            val matchCategory = selectedCategory == "All" || skill.category == selectedCategory
            val matchSearch = searchQuery.isEmpty() ||
                skill.name.contains(searchQuery, ignoreCase = true) ||
                skill.description.contains(searchQuery, ignoreCase = true)
            matchCategory && matchSearch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("skills 市场", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { paing ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .paing(paing),
            contentPaing = PaingValues(bottom = 24.dp),
        ) {
            // ===== 1. Search栏 =====
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .paing(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search skills...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            // ===== 2. minuteClassFilter =====
            item {
                LazyRow(
                    contentPaing = PaingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.paing(vertical = 4.dp),
                ) {
                    items(skillsMarketData.categories) { cat ->
                        FilterChip(
                            selected = cat.label == selectedCategory,
                            onClick = { selectedCategory = cat.label },
                            label = { Text("${cat.emoji} ${cat.label}") },
                        )
                    }
                }
            }

            // ===== 3. maincontent区: 热门 skills =====
            item {
                SectionHeader(
                    title = "🔥 热门 skills",
                    subtitle = "from awesome-openclaw-skills · ${filteredskills.size} count",
                    modifier = Modifier.paing(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            items(filteredskills) { skill ->
                skillCard(
                    skill = skill,
                    onClick = {
                        if (skill.clawhubUrl.isnotEmpty()) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(skill.clawhubUrl)))
                        }
                    },
                    modifier = Modifier.paing(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // ===== 4. 精选合集 =====
            item {
                SectionHeader(
                    title = "[PACKAGE] 精选合集",
                    subtitle = "别人帮你筛good",
                    modifier = Modifier.paing(horizontal = 16.dp, vertical = 16.dp),
                )
            }

            items(skillsMarketData.collections) { collection ->
                collectionCard(
                    collection = collection,
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(collection.url)))
                    },
                    modifier = Modifier.paing(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // ===== 5. 底partAggregateResource =====
            item {
                SectionHeader(
                    title = "📚 moremanyAggregateResource",
                    subtitle = "discovermoremany skills",
                    modifier = Modifier.paing(horizontal = 16.dp, vertical = 16.dp),
                )
            }

            items(skillsMarketData.aggregatedResources) { resource ->
                ResourceRow(
                    resource = resource,
                    onClick = {
                        if (resource.clawhubUrl.isnotEmpty()) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resource.clawhubUrl)))
                        }
                    },
                    modifier = Modifier.paing(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ===== 子Group件 =====

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (subtitle.isnotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun skillCard(
    skill: skillItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.paing(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Spacebetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = Textoverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (skill.downloads.isnotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            Icons.Default.nextload,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            skill.downloads,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = Textoverflow.Ellipsis,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "@${skill.author}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (skill.category.isnotEmpty()) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                skill.category,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun collectionCard(
    collection: skillcollection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.paing(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = collection.coverEmoji,
                fontSize = 28.sp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = collection.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = collection.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = Textoverflow.Ellipsis,
                )
                if (collection.stats.isnotEmpty()) {
                    Text(
                        text = collection.stats,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.OpenInnew,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ResourceRow(
    resource: skillItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.paing(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resource.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = resource.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = Textoverflow.Ellipsis,
                )
            }
            if (resource.downloads.isnotEmpty()) {
                Text(
                    text = resource.downloads,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.OpenInnew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}
