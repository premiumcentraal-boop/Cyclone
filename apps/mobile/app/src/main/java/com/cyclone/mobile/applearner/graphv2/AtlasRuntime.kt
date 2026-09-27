package com.cyclone.mobile.applearner.graphv2

import android.content.Context
import com.cyclone.mobile.applearner.AppKnowledgeStore
import com.cyclone.mobile.brain.graphv2.AtlasReadProvider
import com.cyclone.mobile.brain.graphv2.AtlasRetriever
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.brain.graphv2.PlaceCatalog
import com.cyclone.mobile.brain.graphv2.StoreBackedAtlasReadProvider
import com.cyclone.mobile.places.PlaceResolver
import java.io.File

/** Process facade around the durable phone-owned Atlas. */
object AtlasRuntime {
    @Volatile private var initialized = false

    lateinit var store: AtlasStore
        private set
    lateinit var provider: AtlasReadProvider
        private set
    lateinit var catalog: PlaceCatalog
        private set
    lateinit var retriever: AtlasRetriever
        private set
    lateinit var followMe: FollowMeAtlasPromoter
        private set
    lateinit var legacyImporter: AtlasLegacyImporter
        private set

    @Synchronized
    fun initialize(context: Context, legacyStore: AppKnowledgeStore? = null) {
        if (!initialized) {
            val root = File(context.applicationContext.filesDir, "atlas")
            store = AtlasStore(File(root, "cyclone_atlas_v1.json"))
            provider = StoreBackedAtlasReadProvider(store)
            AtlasGatewayV5Integration.install(provider)
            com.cyclone.mobile.gateway.GatewayV5AppsAdapter.install(context, store)
            com.cyclone.mobile.gateway.GatewayV5KnowledgeAdapter.install(context, store)
            catalog = PlaceCatalog(store)
            retriever = AtlasRetriever(store)
            followMe = FollowMeAtlasPromoter(store)
            legacyImporter = AtlasLegacyImporter(store)
            initialized = true
        }
        legacyStore?.let(::projectLegacy)
    }

    @Synchronized
    fun projectLegacy(legacyStore: AppKnowledgeStore) {
        if (!initialized) return
        legacyStore.listApps().forEach { app ->
            // Legacy graphs are package-scoped and cannot prove a Chrome website origin.
            if (PlaceResolver.isChromePackage(app.packageName)) return@forEach
            legacyStore.graph(app.packageName)?.let(legacyImporter::import)
        }
    }
}
