package com.beettechnologies.posly.products.search

import com.beettechnologies.posly.products.Product
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory

data class ProductSearchResult(val ids: List<String>, val total: Int)

/**
 * Embedded Lucene index kept in lock-step with ProductService's create/update/delete
 * calls (the "indexing pipeline"), since there is no separate persistence layer or
 * message queue in this codebase to drive an async pipeline off of.
 */
class ProductSearchIndex {
    private val analyzer = StandardAnalyzer()
    private val directory = ByteBuffersDirectory()
    private val writer = IndexWriter(directory, IndexWriterConfig(analyzer))
    private val searcherManager = SearcherManager(writer, null)

    fun index(product: Product) {
        val doc = Document()
        doc.add(StringField(FIELD_ID, product.id, Field.Store.YES))
        doc.add(TextField(FIELD_SKU, product.sku, Field.Store.NO))
        doc.add(TextField(FIELD_NAME, product.name, Field.Store.NO))
        product.description?.let { doc.add(TextField(FIELD_DESCRIPTION, it, Field.Store.NO)) }
        product.barcode?.let { doc.add(StringField(FIELD_BARCODE, it, Field.Store.NO)) }
        doc.add(StringField(FIELD_CATEGORY, (product.category ?: "").lowercase(), Field.Store.NO))
        doc.add(StringField(FIELD_IN_STOCK, product.inStock.toString(), Field.Store.NO))
        writer.updateDocument(Term(FIELD_ID, product.id), doc)
        refresh()
    }

    fun remove(id: String) {
        writer.deleteDocuments(Term(FIELD_ID, id))
        refresh()
    }

    fun search(
        query: String?,
        barcode: String?,
        category: String?,
        inStock: Boolean?,
        page: Int,
        size: Int
    ): ProductSearchResult {
        val searcher = searcherManager.acquire()
        try {
            val luceneQuery = buildQuery(query, barcode, category, inStock)
            val topN = ((page + 1) * size).coerceAtLeast(1)
            val topDocs = searcher.search(luceneQuery, topN)
            val fromIndex = (page * size).coerceIn(0, topDocs.scoreDocs.size)
            val storedFields = searcher.storedFields()
            val ids = topDocs.scoreDocs.drop(fromIndex).take(size).map { scoreDoc ->
                storedFields.document(scoreDoc.doc).get(FIELD_ID)
            }
            return ProductSearchResult(ids, topDocs.totalHits.value.toInt())
        } finally {
            searcherManager.release(searcher)
        }
    }

    private fun buildQuery(query: String?, barcode: String?, category: String?, inStock: Boolean?): Query {
        if (!barcode.isNullOrBlank()) {
            return TermQuery(Term(FIELD_BARCODE, barcode))
        }

        val builder = BooleanQuery.Builder()
        val terms = query?.trim()?.lowercase()?.split(WHITESPACE)?.filter { it.isNotBlank() } ?: emptyList()
        if (terms.isEmpty()) {
            builder.add(MatchAllDocsQuery(), BooleanClause.Occur.MUST)
        } else {
            terms.forEach { term ->
                val perTermQuery = BooleanQuery.Builder()
                    .add(PrefixQuery(Term(FIELD_NAME, term)), BooleanClause.Occur.SHOULD)
                    .add(PrefixQuery(Term(FIELD_DESCRIPTION, term)), BooleanClause.Occur.SHOULD)
                    .add(PrefixQuery(Term(FIELD_SKU, term)), BooleanClause.Occur.SHOULD)
                    .build()
                builder.add(perTermQuery, BooleanClause.Occur.MUST)
            }
        }
        if (!category.isNullOrBlank()) {
            builder.add(TermQuery(Term(FIELD_CATEGORY, category.lowercase())), BooleanClause.Occur.MUST)
        }
        if (inStock != null) {
            builder.add(TermQuery(Term(FIELD_IN_STOCK, inStock.toString())), BooleanClause.Occur.MUST)
        }
        return builder.build()
    }

    private fun refresh() {
        writer.commit()
        searcherManager.maybeRefresh()
    }

    private companion object {
        const val FIELD_ID = "id"
        const val FIELD_SKU = "sku"
        const val FIELD_NAME = "name"
        const val FIELD_DESCRIPTION = "description"
        const val FIELD_BARCODE = "barcode"
        const val FIELD_CATEGORY = "category"
        const val FIELD_IN_STOCK = "inStock"
        val WHITESPACE = Regex("\\s+")
    }
}
