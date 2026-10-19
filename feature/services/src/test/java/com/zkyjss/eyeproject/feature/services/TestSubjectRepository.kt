package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.data.subject.SubjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** 测试替身：成员存在内存里，不同步。 */
class TestSubjectRepository(initial: List<SubjectProfile> = emptyList()) : SubjectRepository {
    private val state = MutableStateFlow(initial)
    override val subjects: StateFlow<List<SubjectProfile>> = state
    override val remoteIds = state.map { emptyMap<String, String>() }
    override suspend fun save(profile: SubjectProfile) = state.update { list -> list.filterNot { it.id == profile.id } + profile }
    override suspend fun delete(id: String) = state.update { list -> list.filterNot { it.id == id } }
    override suspend fun sync() = false
}
