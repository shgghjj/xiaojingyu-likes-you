package com.pockettavern.app.ui.screens.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledLive2DModelsTest {
    @Test
    fun bundledModelsHaveUniqueAssetPaths() {
        assertEquals(6, bundledLive2DModels.size)
        assertEquals(bundledLive2DModels.size, bundledLive2DModels.map { it.id }.distinct().size)
        assertEquals(bundledLive2DModels.size, bundledLive2DModels.map { it.modelPath }.distinct().size)
        assertTrue(
            bundledLive2DModels.all {
                it.modelPath.startsWith("/assets/live2d/models/") &&
                    it.modelPath.endsWith(".model3.json")
            }
        )
    }
}
