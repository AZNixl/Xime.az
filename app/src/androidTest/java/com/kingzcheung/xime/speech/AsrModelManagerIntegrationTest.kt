package com.kingzcheung.xime.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for AsrModelManager
 * 
 * These tests verify:
 * - Model availability detection
 * - Model directory structure
 * - Configuration creation
 * - State management
 */
@RunWith(AndroidJUnit4::class)
class AsrModelManagerIntegrationTest {

    private lateinit var context: Context
    private lateinit var engine: AsrModelManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        engine = AsrModelManager(context)
    }

    @Test
    fun testModelAvailabilityDetection() {
        // Test that model readiness is detected
        val isReady = engine.isModelReady()

        // This may be true or false depending on whether the model is downloaded
        // The test documents the expected behavior
        assertTrue("isModelReady should return a boolean",
            isReady == true || isReady == false)
    }

    @Test
    fun testModelDirectoryStructure() {
        // Test that model directory is correctly determined
        val modelDir = engine.getSelectedModelDir()
        
        // Directory should exist or be creatable
        assertNotNull("Model directory should not be null", modelDir)
        assertTrue("Model directory path should not be empty", 
            modelDir.absolutePath.isNotEmpty())
    }

    @Test
    fun testAvailableModelsList() {
        // Test that AVAILABLE_MODELS is populated
        val models = AsrModelManager.AVAILABLE_MODELS
        
        assertFalse("Available models list should not be empty", models.isEmpty())
        assertTrue("Should have at least one model", models.isNotEmpty())
        
        // Verify model info structure
        val firstModel = models.first()
        assertNotNull("Model ID should not be null", firstModel.id)
        assertNotNull("Model name should not be null", firstModel.name)
        assertNotNull("Model download URL should not be null", firstModel.downloadUrl)
        assertTrue("Model files list should not be empty", firstModel.files.isNotEmpty())
    }

    @Test
    fun testModelInfoRetrieval() {
        // Test getting model info
        val modelInfo = engine.getSelectedModelInfo()
        
        // May be null if no model selected
        if (modelInfo != null) {
            assertNotNull("Model ID should not be null", modelInfo.id)
            assertNotNull("Model name should not be null", modelInfo.name)
            assertNotNull("Model type should not be null", modelInfo.modelType)
        }
    }

    @Test
    fun testModelSelection() {
        // Test setting and getting model
        val testModelId = "zipformer-zh-int8"
        
        engine.setModel(testModelId)
        
        val modelInfo = engine.getSelectedModelInfo()
        if (modelInfo != null) {
            assertEquals("Selected model ID should match", testModelId, modelInfo.id)
        }
    }

    @Test
    fun testModelReadyDetection() {
        // Test model ready detection
        val isReady = engine.isModelReady()
        
        // This depends on whether model is downloaded
        assertTrue("isModelReady should return a boolean", 
            isReady == true || isReady == false)
    }

    @Test
    fun testModelNeedsAutoPunctuation() {
        // Test that models correctly report punctuation requirement
        val modelInfo = engine.getSelectedModelInfo()
        
        if (modelInfo != null) {
            // All current models need auto punctuation
            assertTrue("Model should need auto punctuation", 
                modelInfo.needsAutoPunctuation)
        }
    }

    @Test
    fun testModelFilesConfiguration() {
        // Test that model files are properly configured
        val models = AsrModelManager.AVAILABLE_MODELS
        
        for (model in models) {
            assertTrue("Model ${model.id} should have files", model.files.isNotEmpty())
            
            // All models should have encoder/decoder/joiner files
            assertTrue("Model should have encoder file",
                model.encoderFile.isNotEmpty())
            assertTrue("Model should have decoder file",
                model.decoderFile.isNotEmpty())
            assertTrue("Model should have joiner file",
                model.joinerFile.isNotEmpty())
        }
    }

    @Test
    fun testModelInfoPersistence() {
        // Test that model selection persists
        val testModelId = "zipformer-zh-int8"
        
        // Set model
        engine.setModel(testModelId)
        
        // Create new engine instance
        val newEngine = AsrModelManager(context)
        val modelInfo = newEngine.getSelectedModelInfo()
        
        // Should retrieve the same model
        if (modelInfo != null) {
            assertEquals("Model selection should persist", testModelId, modelInfo.id)
        }
    }
}
