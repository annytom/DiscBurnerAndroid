package com.enterprise.discburner.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enterprise.discburner.ui.theme.DiscBurnerTheme
import com.enterprise.discburner.usb.BurnerModel
import com.enterprise.discburner.usb.BurnerModelDatabase
import com.enterprise.discburner.usb.WriteMode

/**
 * 设备选择Activity
 */
class DeviceSelectionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vendorId = intent.getIntExtra("vendorId", 0)
        val productId = intent.getIntExtra("productId", 0)
        val currentModelId = intent.getStringExtra("currentModelId")

        setContent {
            DiscBurnerTheme {
                DeviceSelectionScreen(
                    detectedVendorId = vendorId,
                    detectedProductId = productId,
                    currentModelId = currentModelId,
                    onModelSelected = { model ->
                        // 返回选择的型号
                        intent.putExtra("selectedModelId", model.modelId)
                        setResult(RESULT_OK, intent)
                        finish()
                    },
                    onAutoDetect = {
                        // 使用自动检测的型号
                        val autoModel = BurnerModelDatabase.autoDetect(vendorId, productId)
                        intent.putExtra("selectedModelId", autoModel.modelId)
                        setResult(RESULT_OK, intent)
                        finish()
                    },
                    onNavigateBack = {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionScreen(
    detectedVendorId: Int? = null,
    detectedProductId: Int? = null,
    currentModelId: String? = null,
    onModelSelected: (BurnerModel) -> Unit,
    onAutoDetect: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var selectedBrand by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showAutoDetected by remember { mutableStateOf(true) }

    val brands = remember { BurnerModelDatabase.getAllBrands() }

    // 自动检测的型号
    val autoDetectedModel = remember(detectedVendorId, detectedProductId) {
        if (detectedVendorId != null && detectedVendorId != 0) {
            BurnerModelDatabase.autoDetect(detectedVendorId, detectedProductId)
        } else null
    }

    // 过滤后的型号列表
    val filteredModels = remember(selectedBrand, searchQuery) {
        var models = BurnerModelDatabase.knownModels

        if (selectedBrand != null) {
            models = models.filter { it.brand == selectedBrand }
        }

        if (searchQuery.isNotBlank()) {
            models = models.filter {
                it.model.contains(searchQuery, ignoreCase = true) ||
                        it.brand.contains(searchQuery, ignoreCase = true) ||
                        it.description.contains(searchQuery, ignoreCase = true)
            }
        }

        models
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择刻录机型号") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (autoDetectedModel != null) {
                        TextButton(onClick = onAutoDetect) {
                            Text("自动检测")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 自动检测结果卡片
            if (autoDetectedModel != null && showAutoDetected) {
                AutoDetectedCard(
                    model = autoDetectedModel,
                    onSelect = { onModelSelected(autoDetectedModel) },
                    onDismiss = { showAutoDetected = false }
                )
            }

            // 搜索栏
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("搜索型号...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
                singleLine = true
            )

            // 品牌筛选
            BrandFilterChips(
                brands = brands,
                selectedBrand = selectedBrand,
                onBrandSelected = { selectedBrand = it }
            )

            // 型号列表
            if (filteredModels.isEmpty()) {
                EmptyModelState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredModels, key = { it.modelId }) { model ->
                        ModelCard(
                            model = model,
                            isSelected = model.modelId == currentModelId,
                            onClick = { onModelSelected(model) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AutoDetectedCard(
    model: BurnerModel,
    onSelect: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "自动检测到刻录机",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, null)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                model.fullName,
                style = MaterialTheme.typography.bodyLarge
            )

            Text(
                model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 特性标签
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (model.features.supportsBufferUnderrunProtection) {
                    FeatureChip("防刻死")
                }
                if (model.features.supportsDVDPlusRDL || model.features.supportsDVDRDL) {
                    FeatureChip("双层")
                }
                if (model.features.supportsLightScribe) {
                    FeatureChip("光雕")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("使用此型号")
            }
        }
    }
}

@Composable
fun BrandFilterChips(
    brands: List<String>,
    selectedBrand: String?,
    onBrandSelected: (String?) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            "品牌筛选",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedBrand == null,
                onClick = { onBrandSelected(null) },
                label = { Text("全部") }
            )

            brands.take(5).forEach { brand ->
                FilterChip(
                    selected = selectedBrand == brand,
                    onClick = { onBrandSelected(brand) },
                    label = { Text(brand) }
                )
            }
        }
    }
}

@Composable
fun ModelCard(
    model: BurnerModel,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        model.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isSelected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 规格信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SpecItem("最大速度", "${model.maxSpeed}x")
                SpecItem("缓冲区", "${model.bufferSize}KB")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 特性标签
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (model.features.supportsDVDPlusRDL || model.features.supportsDVDRDL) {
                    FeatureChip("DVD双层")
                }
                if (model.features.supportsBufferUnderrunProtection) {
                    FeatureChip("防刻死")
                }
                if (model.features.supportsLightScribe) {
                    FeatureChip("LightScribe")
                }
                if (model.features.supportsLabelflash) {
                    FeatureChip("Labelflash")
                }
            }
        }
    }
}

@Composable
fun SpecItem(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun FeatureChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
fun EmptyModelState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Usb,
                null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "未找到匹配的刻录机型号",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "请选择通用型号或手动输入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
