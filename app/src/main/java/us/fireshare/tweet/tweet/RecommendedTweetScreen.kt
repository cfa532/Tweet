package us.fireshare.tweet.tweet

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import us.fireshare.tweet.R

@Composable
fun RecommendedTweetScreen() {
    Text(stringResource(R.string.recommended_tweets))
}