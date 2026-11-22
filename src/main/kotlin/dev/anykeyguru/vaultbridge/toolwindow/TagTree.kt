package dev.anykeyguru.vaultbridge.toolwindow

import dev.anykeyguru.vaultbridge.model.Note

/**
 * Узел дерева вложенных тегов: `project/alpha` лежит под `project`.
 * [notes] — заметки с ровно этим тегом, [count] — уникальные заметки во всём поддереве.
 */
class TagNode(val name: String, val fullTag: String) {
    val children: MutableMap<String, TagNode> = sortedMapOf()
    val notes: MutableList<Note> = mutableListOf()
    var count: Int = 0
        internal set

    companion object {
        /** [tags] — как в [dev.anykeyguru.vaultbridge.index.VaultIndex.tags]: тег в нижнем регистре → заметки. */
        fun build(tags: Map<String, List<Note>>): TagNode {
            val root = TagNode("", "")
            for ((tag, notes) in tags) {
                var node = root
                for (part in tag.split('/').filter { it.isNotEmpty() }) {
                    val full = if (node.fullTag.isEmpty()) part else node.fullTag + "/" + part
                    node = node.children.getOrPut(part) { TagNode(part, full) }
                }
                if (node === root) continue
                val seen = node.notes.mapTo(HashSet()) { it.path }
                notes.filter { seen.add(it.path) }.forEach(node.notes::add)
                node.notes.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }
            countSubtree(root)
            return root
        }

        private fun countSubtree(node: TagNode): Set<String> {
            val paths = node.notes.mapTo(HashSet()) { it.path }
            for (child in node.children.values) paths += countSubtree(child)
            node.count = paths.size
            return paths
        }
    }
}
