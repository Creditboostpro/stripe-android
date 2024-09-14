package com.stripe.android.connectsdk.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stripe.android.connectsdk.example.ui.accountonboarding.AccountOnboardingExampleActivity
import com.stripe.android.connectsdk.example.ui.payouts.PayoutsExampleActivity

class MainActivity : ComponentActivity() {

    private data class MenuItem(
        val title: String,
        val subtitle: String,
        val activity: Class<out ComponentActivity>,
        val isBeta: Boolean = false,
    )

    private val menuItems = listOf(
        MenuItem(
            title = "Account Onboarding",
            subtitle = "Show a localized onboarding form that validates data",
            activity = AccountOnboardingExampleActivity::class.java,
            isBeta = true,
        ),
        MenuItem(
            title = "Payouts",
            subtitle = "Show payout information and allow your users to perform payouts",
            activity = PayoutsExampleActivity::class.java,
            isBeta = true,
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ConnectSdkExampleTheme {
                MainContent(title = "Connect SDK Example") {
                    ComponentList(menuItems)
                }
            }
        }
    }

    @Composable
    private fun ComponentList(components: List<MenuItem>) {
        LazyColumn {
            items(components) { menuItem ->
                MenuRowItem(menuItem)
            }
        }
    }

    @Composable
    private fun LazyItemScope.MenuRowItem(menuItem: MenuItem) {
        val context = LocalContext.current
        Row(
            modifier = Modifier
                .fillParentMaxWidth()
                .clickable { context.startActivity(Intent(context, menuItem.activity)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = menuItem.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (menuItem.isBeta) {
                            BetaBadge()
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(

                        text = menuItem.subtitle,
                        fontSize = 16.sp,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
            }

            Icon(
                modifier = Modifier
                    .size(36.dp)
                    .padding(start = 8.dp),
                contentDescription = null,
                imageVector = Icons.Default.KeyboardArrowRight,
            )
        }
    }

    @Composable
    private fun BetaBadge() {
        val borderColor = Color(0xffa7e7fc) // none
        val backgroundColor = Color(0xffcbf5fd) // Color(0xff051a4c)
        val textColor = Color(0xff045ad0) // none
        val shape = RoundedCornerShape(4.dp)
        val labelMediumEmphasized = TextStyle.Default.copy(
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 20.sp,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None
            )
        )
        Text(
            modifier = Modifier
                .border(1.dp, borderColor, shape)
                .background(
                    color = backgroundColor,
                    shape = shape
                )
                .padding(horizontal = 6.dp, vertical = 1.dp),
            color = textColor,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            style = labelMediumEmphasized,
            text = "BETA"
        )
    }

    @Composable
    @Preview(showBackground = true)
    fun ComponentListPreview() {
        ConnectSdkExampleTheme {
            ComponentList(menuItems)
        }
    }

    @Composable
    @Preview(showBackground = true)
    fun BetaBadgePreview() {
        ConnectSdkExampleTheme {
            BetaBadge()
        }
    }
}