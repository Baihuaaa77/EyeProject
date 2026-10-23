package com.zkyjss.eyeproject.feature.mine.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.mine.AccountScreen
import com.zkyjss.eyeproject.feature.mine.CloudSyncScreen
import com.zkyjss.eyeproject.feature.mine.MineNavigation
import com.zkyjss.eyeproject.feature.mine.MineScreen
import com.zkyjss.eyeproject.feature.mine.PermissionsScreen
import com.zkyjss.eyeproject.feature.mine.RecordsScreen
import com.zkyjss.eyeproject.feature.mine.SubjectEditScreen
import com.zkyjss.eyeproject.feature.mine.SubjectsScreen
import kotlinx.serialization.Serializable

@Serializable
data object MineRoute

/** 编辑被测者资料；[id] 为空表示新建家庭成员。 */
@Serializable
data class SubjectEditRoute(val id: String? = null)

/** 右上角人像按钮进入的账户页（三个顶层页共用）。 */
@Serializable
data object AccountRoute

@Serializable
data object RecordsRoute

@Serializable
data object SubjectsRoute

@Serializable
data object CloudSyncRoute

@Serializable
data object PermissionsRoute

fun NavController.navigateToMine(navOptions: NavOptions? = null) {
    navigate(MineRoute, navOptions)
}

fun NavController.navigateToSubjectEdit(id: String?) {
    navigate(SubjectEditRoute(id))
}

fun NavController.navigateToAccount() { navigate(AccountRoute) { launchSingleTop = true } }
fun NavController.navigateToRecords() { navigate(RecordsRoute) { launchSingleTop = true } }
fun NavController.navigateToSubjects() { navigate(SubjectsRoute) { launchSingleTop = true } }
fun NavController.navigateToCloudSync() { navigate(CloudSyncRoute) { launchSingleTop = true } }
fun NavController.navigateToPermissions() { navigate(PermissionsRoute) { launchSingleTop = true } }

fun NavGraphBuilder.mineScreen(navigation: MineNavigation) {
    composable<MineRoute> { MineScreen(navigation) }
}

fun NavGraphBuilder.subjectEditScreen(onBack: () -> Unit) {
    composable<SubjectEditRoute> {
        SubjectEditScreen(onBack = onBack)
    }
}

/** 「我的」的二级完整页面：账户、测试记录、家庭成员、云端同步、权限与通知。 */
fun NavGraphBuilder.mineSubScreens(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenCloudSync: () -> Unit,
    onEditSubject: (String?) -> Unit,
) {
    composable<AccountRoute> { AccountScreen(onBack = onBack, onOpenCloudSync = onOpenCloudSync) }
    composable<RecordsRoute> { RecordsScreen(onBack = onBack) }
    composable<SubjectsRoute> { SubjectsScreen(onBack = onBack, onEdit = onEditSubject) }
    composable<CloudSyncRoute> { CloudSyncScreen(onBack = onBack, onOpenAccount = onOpenAccount) }
    composable<PermissionsRoute> { PermissionsScreen(onBack = onBack) }
}
