package com.awesome.dhs.tools.downloader

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.awesome.dhs.tools.downloader.ui.theme.DownloaderTheme
import kotlinx.coroutines.launch
import kotlin.getValue

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel: DownloadViewModel by viewModels()

        enableEdgeToEdge()
        setContent {
            val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
            val tabs = listOf(
                "DOWNLOAD",
                "SETTINGS"
            )
            val coroutineScope = rememberCoroutineScope()
            DownloaderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = pagerState.currentPage == index,
                                    onClick = { // 点击 Tab 时，动画地滚动到对应的页面
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    },
                                    text = {
                                        Text(
                                            title,
                                            color = if (pagerState.currentPage == index) LocalContentColor.current else MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                )
                            }
                        }
                        // 使用 HorizontalPager 显示不同内容
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            when (page) {
                                0 -> DownloadScreen(viewModel)
                                1 -> FolderSelectionScreen(onFolderSelectedForDownload = { uri ->
                                    Downloader.updateConfig { it.copy(finalDirectory = uri.toString()) }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DownloaderTheme {
        Greeting("Android")
    }
}