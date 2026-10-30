package com.lightmeter.rawmeter

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest

/** Asset loading is performed once; prediction never opens a file or creates an interpreter. */
internal object GlobalMeteringModel {
    @Volatile private var network: GlobalMeteringNetwork? = null

    @Synchronized fun initialize(assets: AssetManager) {
        if (network != null) return
        try {
            val metadata = JSONObject(assets.open("global_metering_epoch219.json").bufferedReader().use { it.readText() })
            require(metadata.getInt("epoch") == GlobalMeteringNetwork.EPOCH)
            require(metadata.getString("feature_version") == GlobalMeteringInputMapping.FEATURE_VERSION)
            require(metadata.getString("domain_version") == GlobalMeteringInputMapping.DOMAIN_VERSION)
            require(metadata.getString("mapping_version") == GlobalMeteringInputMapping.MAPPING_VERSION)
            val bytes = assets.open("global_metering_epoch219.bin").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(digest == metadata.getString("weights_sha256"))
            network = GlobalMeteringNetwork.decode(bytes)
            Log.i("GlobalMeteringModel", "Loaded FP32 metering epoch 219, ${bytes.size} bytes")
        } catch (error: Exception) {
            Log.e("GlobalMeteringModel", "Model unavailable; using global statistical metering", error)
        }
    }

    fun predict(input: GlobalMeteringInput): GlobalMeteringPrediction? {
        if (input.domainVersion != GlobalMeteringInputMapping.DOMAIN_VERSION ||
            input.featureVersion != GlobalMeteringInputMapping.FEATURE_VERSION) return null
        return network?.predict(input.features)
    }
}
