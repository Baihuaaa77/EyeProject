package com.zkyjss.eyeproject.feature.mine

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.zkyjss.eyeproject.core.data.subject.SubjectRepository
import com.zkyjss.eyeproject.feature.mine.navigation.SubjectEditRoute
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import com.zkyjss.eyeproject.feature.services.SubjectProfiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 资料编辑页状态：[draft] 为正在编辑的副本（未保存前不影响测试）；为空表示加载中。 */
data class SubjectEditUiState(
    val draft: SubjectProfile? = null,
    val isNew: Boolean = false,
    /** 出生年份等输入无效时的提示。 */
    val error: String? = null,
)

@HiltViewModel
class SubjectEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val subjects: SubjectRepository,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<SubjectEditRoute>().id
    private val _uiState = MutableStateFlow(SubjectEditUiState())
    val uiState: StateFlow<SubjectEditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = id?.let { wanted -> subjects.subjects.first().firstOrNull { it.id == wanted } }
            _uiState.value = SubjectEditUiState(
                draft = existing ?: SubjectProfile(UUID.randomUUID().toString(), "CHILD", "", emptyMap(), 0L),
                isNew = existing == null,
            )
        }
    }

    fun setNickname(value: String) = edit { it.copy(nickname = value.take(32)) }
    fun setRelation(value: String) = edit { if (it.relation == "SELF" || value == "SELF") it else it.copy(relation = value) }
    fun setField(key: String, value: String) = edit {
        it.copy(fields = if (value.isBlank()) it.fields - key else it.fields + (key to value))
    }

    private fun edit(change: (SubjectProfile) -> SubjectProfile) =
        _uiState.update { s -> s.copy(draft = s.draft?.let(change), error = null) }

    /** 保存：出生年份等填写了就必须有效；称呼为空时用关系名代替。 */
    fun save(onDone: () -> Unit) {
        val draft = _uiState.value.draft ?: return
        val invalid = SubjectProfiles.questions.firstOrNull { q ->
            q.visibleWhen(draft.fields) && draft.fields[q.key].orEmpty().let { it.isNotBlank() && !q.validate(it) }
        }
        if (invalid != null) { _uiState.update { it.copy(error = "「${invalid.title}」填写有误") }; return }
        // 隐藏的追问（如取消了手术后的手术类型）不再保存
        val visible = SubjectProfiles.questions.filter { it.visibleWhen(draft.fields) }.mapTo(HashSet()) { it.key }
        viewModelScope.launch {
            subjects.save(draft.copy(fields = draft.fields.filterKeys { it in visible }))
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val draft = _uiState.value.draft ?: return
        viewModelScope.launch { subjects.delete(draft.id); onDone() }
    }
}
