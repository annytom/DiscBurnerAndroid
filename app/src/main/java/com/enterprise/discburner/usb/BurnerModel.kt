package com.enterprise.discburner.usb

/**
 * 刻录机型号信息
 *
 * @property modelId 型号唯一ID
 * @property brand 品牌
 * @property model 型号名称
 * @property vendorId USB Vendor ID
 * @property productId USB Product ID
 * @property maxSpeed 最大刻录速度 (x)
 * @property supportedModes 支持的刻录模式
 * @property bufferSize 缓冲区大小 (KB)
 * @property features 特性标志
 */
data class BurnerModel(
    val modelId: String,
    val brand: String,
    val model: String,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val maxSpeed: Int = 52,  // 默认52x
    val supportedModes: List<WriteMode> = WriteMode.values().toList(),
    val bufferSize: Int = 2048,  // 默认2MB缓冲区
    val features: BurnerFeatures = BurnerFeatures(),
    val description: String = ""
) {
    val displayName: String
        get() = "$brand $model"

    val fullName: String
        get() = "$brand $model (Max ${maxSpeed}x)"
}

/**
 * 刻录机特性
 */
data class BurnerFeatures(
    val supportsBufferUnderrunProtection: Boolean = true,  // 防刻死技术
    val supportsMountRainier: Boolean = false,  // Mount Rainier支持
    val supportsLightScribe: Boolean = false,   // LightScribe标签
    val supportsLabelflash: Boolean = false,    // Labelflash标签
    val supportsDVD: Boolean = true,            // DVD支持
    val supportsDVDPlusRW: Boolean = true,      // DVD+RW支持
    val supportsDVDRW: Boolean = true,          // DVD-RW支持
    val supportsDVDPlusRDL: Boolean = false,    // DVD+R DL双层
    val supportsDVDRDL: Boolean = false,        // DVD-R DL双层
    val supportsCDRW: Boolean = true,           // CD-RW支持
    val supportsCDText: Boolean = true,         // CD-TEXT支持
    val supportsSmartBurn: Boolean = false      // Smart-Burn技术
)

/**
 * 已知刻录机型号数据库
 */
object BurnerModelDatabase {

    /**
     * 预定义的刻录机型号列表
     */
    val knownModels = listOf(
        // ASUS 系列
        BurnerModel(
            modelId = "ASUS_DRW_24B3ST",
            brand = "ASUS",
            model = "DRW-24B3ST",
            vendorId = 0x13FD,
            maxSpeed = 24,
            bufferSize = 1536,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = true,
                supportsDVDRDL = true
            ),
            description = "ASUS 24倍速DVD刻录机，支持双层刻录"
        ),
        BurnerModel(
            modelId = "ASUS_SDRW_08D2S",
            brand = "ASUS",
            model = "SDRW-08D2S-U",
            vendorId = 0x13FD,
            maxSpeed = 8,
            bufferSize = 1024,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = false,
                supportsDVDRDL = false
            ),
            description = "ASUS 超薄外置DVD刻录机"
        ),

        // LG 系列
        BurnerModel(
            modelId = "LG_GH24NSD1",
            brand = "LG",
            model = "GH24NSD1",
            vendorId = 0x152E,
            maxSpeed = 24,
            bufferSize = 2048,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = true,
                supportsDVDRDL = true,
                supportsLightScribe = false
            ),
            description = "LG 24倍速SATA接口DVD刻录机"
        ),
        BurnerModel(
            modelId = "LG_GP65NB60",
            brand = "LG",
            model = "GP65NB60",
            vendorId = 0x152E,
            maxSpeed = 8,
            bufferSize = 1024,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = true,
                supportsDVDRDL = true
            ),
            description = "LG 超薄外置DVD刻录机，Type-C接口"
        ),

        // Samsung 系列
        BurnerModel(
            modelId = "Samsung_SE_208GB",
            brand = "Samsung",
            model = "SE-208GB",
            vendorId = 0x04E8,
            maxSpeed = 8,
            bufferSize = 1536,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = true,
                supportsDVDRDL = true
            ),
            description = "Samsung 超薄外置DVD刻录机"
        ),
        BurnerModel(
            modelId = "Samsung_SH_224FB",
            brand = "Samsung",
            model = "SH-224FB",
            vendorId = 0x04E8,
            maxSpeed = 24,
            bufferSize = 1536,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = true,
                supportsDVDRDL = true
            ),
            description = "Samsung 24倍速DVD刻录机"
        ),

        // Lite-On 系列
        BurnerModel(
            modelId = "LiteOn_IHAS324",
            brand = "Lite-On",
            model = "iHAS324",
            vendorId = 0x059B,
            maxSpeed = 24,
            bufferSize = 2048,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = true,
                supportsDVDRDL = true,
                supportsSmartBurn = true
            ),
            description = "Lite-On 24倍速DVD刻录机，Smart-Burn技术"
        ),
        BurnerModel(
            modelId = "LiteOn_eBAU108",
            brand = "Lite-On",
            model = "eBAU108",
            vendorId = 0x059B,
            maxSpeed = 8,
            bufferSize = 1024,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true
            ),
            description = "Lite-On 超薄外置DVD刻录机"
        ),

        // Pioneer 系列
        BurnerModel(
            modelId = "Pioneer_DVR_S21WBK",
            brand = "Pioneer",
            model = "DVR-S21WBK",
            vendorId = 0x057B,
            maxSpeed = 24,
            bufferSize = 2048,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = true,
                supportsDVDRDL = true,
                supportsLabelflash = true
            ),
            description = "Pioneer 24倍速DVD刻录机，支持Labelflash"
        ),

        // Sony 系列
        BurnerModel(
            modelId = "Sony_AD_7290H",
            brand = "Sony",
            model = "AD-7290H",
            vendorId = 0x054C,
            maxSpeed = 24,
            bufferSize = 2048,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = true,
                supportsDVDRDL = true
            ),
            description = "Sony 24倍速DVD刻录机"
        ),
        BurnerModel(
            modelId = "Sony_DRX_S90U",
            brand = "Sony",
            model = "DRX-S90U",
            vendorId = 0x054C,
            maxSpeed = 8,
            bufferSize = 1024,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true
            ),
            description = "Sony 超薄外置DVD刻录机"
        ),

        // HP 系列
        BurnerModel(
            modelId = "HP_DVD1270I",
            brand = "HP",
            model = "DVD1270i",
            vendorId = 0x03F0,
            maxSpeed = 24,
            bufferSize = 2048,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsLightScribe = true
            ),
            description = "HP 24倍速DVD刻录机，支持LightScribe"
        ),
        BurnerModel(
            modelId = "HP_DVD556S",
            brand = "HP",
            model = "DVD556s",
            vendorId = 0x03F0,
            maxSpeed = 8,
            bufferSize = 1024,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true
            ),
            description = "HP 超薄外置DVD刻录机"
        ),

        // Buffalo 系列
        BurnerModel(
            modelId = "Buffalo_DVSM_PC58U2V",
            brand = "Buffalo",
            model = "DVSM-PC58U2V",
            vendorId = 0x07AB,
            maxSpeed = 8,
            bufferSize = 2048,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true,
                supportsDVDPlusRDL = true,
                supportsDVDRDL = true
            ),
            description = "Buffalo 外置DVD刻录机"
        ),

        // Transcend 系列
        BurnerModel(
            modelId = "Transcend_TS8XDVDS",
            brand = "Transcend",
            model = "TS8XDVDS",
            vendorId = 0x058F,
            maxSpeed = 8,
            bufferSize = 2048,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true
            ),
            description = "Transcend 超薄外置DVD刻录机"
        ),

        // 通用/未知型号
        BurnerModel(
            modelId = "GENERIC_CD_RW",
            brand = "通用",
            model = "CD-RW刻录机",
            maxSpeed = 52,
            supportedModes = listOf(WriteMode.DAO, WriteMode.TAO),
            bufferSize = 2048,
            features = BurnerFeatures(
                supportsDVD = false,
                supportsBufferUnderrunProtection = true
            ),
            description = "通用CD-RW刻录机"
        ),
        BurnerModel(
            modelId = "GENERIC_DVD_RW",
            brand = "通用",
            model = "DVD±RW刻录机",
            maxSpeed = 24,
            bufferSize = 2048,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true
            ),
            description = "通用DVD刻录机（自动识别）"
        ),
        BurnerModel(
            modelId = "GENERIC_EXTERNAL",
            brand = "通用",
            model = "外置USB刻录机",
            maxSpeed = 8,
            bufferSize = 1024,
            features = BurnerFeatures(
                supportsBufferUnderrunProtection = true
            ),
            description = "通用USB外置刻录机（自动识别）"
        )
    )

    /**
     * 根据VID/PID查找匹配的型号
     */
    fun findByUsbId(vendorId: Int, productId: Int): BurnerModel? {
        return knownModels.find { model ->
            model.vendorId == vendorId && model.productId == productId
        }
    }

    /**
     * 根据型号ID查找
     */
    fun findByModelId(modelId: String): BurnerModel? {
        return knownModels.find { it.modelId == modelId }
    }

    /**
     * 根据品牌获取型号列表
     */
    fun getModelsByBrand(brand: String): List<BurnerModel> {
        return knownModels.filter { it.brand == brand }
    }

    /**
     * 获取所有品牌列表
     */
    fun getAllBrands(): List<String> {
        return knownModels.map { it.brand }.distinct()
    }

    /**
     * 自动检测型号（基于VID）
     */
    fun autoDetect(vendorId: Int, productId: Int? = null): BurnerModel {
        // 先尝试精确匹配
        if (productId != null) {
            findByUsbId(vendorId, productId)?.let { return it }
        }

        // 根据VID推断品牌
        val detected = when (vendorId) {
            0x13FD -> knownModels.find { it.modelId == "ASUS_DRW_24B3ST" }
            0x152E -> knownModels.find { it.modelId == "LG_GH24NSD1" }
            0x04E8 -> knownModels.find { it.modelId == "Samsung_SH_224FB" }
            0x059B -> knownModels.find { it.modelId == "LiteOn_IHAS324" }
            0x057B -> knownModels.find { it.modelId == "Pioneer_DVR_S21WBK" }
            0x054C -> knownModels.find { it.modelId == "Sony_AD_7290H" }
            0x03F0 -> knownModels.find { it.modelId == "HP_DVD1270I" }
            else -> null
        }

        return detected ?: knownModels.find { it.modelId == "GENERIC_DVD_RW" }!!
    }
}

/**
 * 设备配置
 * 根据选择的型号加载对应配置
 */
data class BurnerConfiguration(
    val model: BurnerModel,
    val selectedSpeed: Int = 0,  // 0 = 自动
    val enableBufferUnderrunProtection: Boolean = true,
    val preferredMode: WriteMode = WriteMode.DAO,
    val customBufferSize: Int? = null
) {
    val effectiveMaxSpeed: Int
        get() = if (selectedSpeed == 0) model.maxSpeed else minOf(selectedSpeed, model.maxSpeed)

    val effectiveBufferSize: Int
        get() = customBufferSize ?: model.bufferSize

    val supportedSpeeds: List<Int>
        get() = generateSpeedOptions(model.maxSpeed)

    companion object {
        fun generateSpeedOptions(maxSpeed: Int): List<Int> {
            val standardSpeeds = listOf(4, 8, 16, 24, 32, 40, 48, 52)
            return listOf(0) + standardSpeeds.filter { it <= maxSpeed }
        }
    }
}
