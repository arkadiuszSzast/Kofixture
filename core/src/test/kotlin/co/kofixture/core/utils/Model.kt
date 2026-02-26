package co.kofixture.core.utils

data class ProjectName(val value: String)

data class Email(val value: String)

data class User(val name: String, val surname: String, val email: Email)

data class Tag(val label: String)

data class Project(val name: ProjectName, val owner: User, val memberCount: Int, val tags: List<Tag>)
