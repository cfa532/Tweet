package us.fireshare.tweet.service

import android.content.Context
import timber.log.Timber

/**
 * Utility class for testing launcher badge functionality.
 * This can be used for debugging and manual testing of badge updates.
 */
object BadgeTestUtility {
    
    /**
     * Test the launcher badge functionality by setting a test count
     * @param context Application context
     * @param testCount Badge count to test with
     */
    fun testBadgeCount(context: Context, testCount: Int) {
        try {
            val formattedText = LauncherBadgeManager.formatBadgeText(testCount)
            Timber.tag("BadgeTestUtility").d("Testing badge count: $testCount (display: '$formattedText')")
            
            // Test direct launcher badge update
            LauncherBadgeManager.updateBadgeCount(context, testCount)
            
            // Test through BadgeStateManager
            BadgeStateManager.updateBadgeCount(testCount)
            
            Timber.tag("BadgeTestUtility").d("Badge test completed for count: $testCount (display: '$formattedText')")
        } catch (e: Exception) {
            Timber.tag("BadgeTestUtility").e(e, "Error testing badge count: $testCount")
        }
    }
    
    /**
     * Test badge formatting logic with various scenarios
     * @param context Application context
     */
    fun testBadgeFormatting(context: Context) {
        try {
            Timber.tag("BadgeTestUtility").d("Testing badge formatting logic...")
            
            // Test various count scenarios
            val testCases = listOf(0, 1, 5, 9, 10, 15, 99, 100)
            
            testCases.forEach { count ->
                val formattedText = LauncherBadgeManager.formatBadgeText(count)
                Timber.tag("BadgeTestUtility").d("Count: $count -> Display: '$formattedText'")
            }
            
            // Test actual badge updates for key scenarios
            testBadgeCount(context, 1)   // Should show "1"
            testBadgeCount(context, 9)   // Should show "9"
            testBadgeCount(context, 10)  // Should show "n"
            testBadgeCount(context, 25)  // Should show "n"
            
            Timber.tag("BadgeTestUtility").d("Badge formatting test completed")
        } catch (e: Exception) {
            Timber.tag("BadgeTestUtility").e(e, "Error testing badge formatting")
        }
    }
    
    /**
     * Test badge support on the current device
     * @param context Application context
     * @return true if supported, false otherwise
     */
    fun testBadgeSupport(context: Context): Boolean {
        return try {
            val isSupported = LauncherBadgeManager.isBadgeSupported(context)
            Timber.tag("BadgeTestUtility").d("Badge support test result: $isSupported")
            isSupported
        } catch (e: Exception) {
            Timber.tag("BadgeTestUtility").e(e, "Error testing badge support")
            false
        }
    }
    
    /**
     * Clear the badge for testing
     * @param context Application context
     */
    fun clearBadge(context: Context) {
        try {
            Timber.tag("BadgeTestUtility").d("Clearing badge for testing")
            LauncherBadgeManager.clearBadge(context)
            BadgeStateManager.clearBadge()
            Timber.tag("BadgeTestUtility").d("Badge cleared successfully")
        } catch (e: Exception) {
            Timber.tag("BadgeTestUtility").e(e, "Error clearing badge")
        }
    }
    
    /**
     * Test badge increment functionality
     * @param context Application context
     */
    fun testBadgeIncrement(context: Context) {
        try {
            Timber.tag("BadgeTestUtility").d("Testing badge increment")
            BadgeStateManager.incrementBadge()
            Timber.tag("BadgeTestUtility").d("Badge increment test completed")
        } catch (e: Exception) {
            Timber.tag("BadgeTestUtility").e(e, "Error testing badge increment")
        }
    }
    
    /**
     * Test the complete badge flow from 1 to 10+ messages
     * @param context Application context
     */
    fun testCompleteBadgeFlow(context: Context) {
        try {
            Timber.tag("BadgeTestUtility").d("Testing complete badge flow...")
            
            // Clear badge first
            clearBadge(context)
            
            // Test incrementing from 1 to 12
            for (i in 1..12) {
                BadgeStateManager.incrementBadge()
                val formattedText = BadgeStateManager.getFormattedBadgeText()
                Timber.tag("BadgeTestUtility").d("Increment $i: count=$i, display='$formattedText'")
                
                // Small delay to see the progression
                Thread.sleep(500)
            }
            
            // Clear at the end
            clearBadge(context)
            
            Timber.tag("BadgeTestUtility").d("Complete badge flow test finished")
        } catch (e: Exception) {
            Timber.tag("BadgeTestUtility").e(e, "Error in complete badge flow test")
        }
    }
} 