package com.fireshare.tweet.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.LocalNavController
import com.fireshare.tweet.R
import com.fireshare.tweet.tweet.CommentItem
import com.fireshare.tweet.tweet.TweetDetailHead
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.BottomNavigationBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    parentEntry: NavBackStackEntry,
    selectedBottomBarItemIndex: Int,
) {
    val navController = LocalNavController.current
    Scaffold(
        topBar = { TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.primary,
            ),
            title = {
                Text(
                    text = "Message",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() })
                {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "Back",
                        modifier = Modifier
                            .size(18.dp)
                    )
                }
            },
        )
        },
        bottomBar = { BottomNavigationBar(navController, selectedBottomBarItemIndex) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text(text = "Message box")
        }
    }
}