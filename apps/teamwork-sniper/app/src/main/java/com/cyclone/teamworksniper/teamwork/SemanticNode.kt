package com.cyclone.teamworksniper.teamwork

data class SemanticNode(val text: String? = null, val contentDescription: String? = null, val resourceId: String? = null, val className: String? = null, val clickable: Boolean = false, val scrollable: Boolean = false, val actions: Set<String> = emptySet(), val sourceChildIndex: Int? = null, val children: List<SemanticNode> = emptyList()) {
    fun ownSemanticText(): String = listOfNotNull(text, contentDescription).joinToString(" ").replace(Regex("\\s+"), " ").trim()
    fun subtreeSemanticText(): String = buildList { ownSemanticText().takeIf { it.isNotBlank() }?.let(::add); children.forEach { it.subtreeSemanticText().takeIf(String::isNotBlank)?.let(::add) } }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
}
data class SemanticRef(val path: List<Int>, val node: SemanticNode, val ancestors: List<SemanticRef>)
fun SemanticNode.flatten(): List<SemanticRef> { val out = mutableListOf<SemanticRef>(); fun visit(n: SemanticNode, p: List<Int>, a: List<SemanticRef>) { val ref = SemanticRef(p,n,a); out += ref; n.children.forEachIndexed { i,c -> visit(c,p+(c.sourceChildIndex ?: i),a+ref) } }; visit(this, emptyList(), emptyList()); return out }
