package com.zkyjss.eyeproject.feature.mine.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.zkyjss.eyeproject.feature.mine.MineScreen
import com.zkyjss.eyeproject.feature.mine.SubjectEditScreen
import kotlinx.serialization.Serializable

@Serializable
data object MineRoute

/** 编辑被测者资料；[id] 为空表示新建家庭成员。 */
@Serializable
data class SubjectEditRoute(val id: String? = null)

fun NavController.navigateToMine(navOptions: NavOptions? = null) {
    navigate(MineRoute, navOptions)
}

fun NavController.navigateToSubjectEdit(id: String?) {
    navigate(SubjectEditRoute(id))
}

fun NavGraphBuilder.mineScreen(onEditSubject: (String?) -> Unit) {
    composable<MineRoute> {
        MineScreen(onEditSubject = onEditSubject)
    }
}

fun NavGraphBuilder.subjectEditScreen(onBack: () -> Unit) {
    composable<SubjectEditRoute> {
        SubjectEditScreen(onBack = onBack)
    }
}
