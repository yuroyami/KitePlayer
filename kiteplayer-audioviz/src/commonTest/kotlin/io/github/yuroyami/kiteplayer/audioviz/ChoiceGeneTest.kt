package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.motion.ChoiceGene
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Genes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A choice fades from the old option over one cycle, also when a second change lands inside the first. */
class ChoiceGeneTest {

    private fun gene(): ChoiceGene = Genes(1L).choice("shape", 3)

    private fun weights(gene: ChoiceGene) = List(3) { gene.weight(it) }

    @Test
    fun aChangeFadesFromTheOldOptionToTheNewOne() {
        val gene = gene()
        assertEquals(listOf(1f, 0f, 0f), weights(gene))
        gene.choose(1)
        assertEquals(listOf(1f, 0f, 0f), weights(gene), "a change starts from the old picture")
        gene.advance(1f, 2f)
        assertEquals(listOf(0.5f, 0.5f, 0f), weights(gene))
        gene.advance(1f, 2f)
        assertEquals(listOf(0f, 1f, 0f), weights(gene))
    }

    @Test
    fun aSecondChangeInsideTheFirstStartsWhereThePictureIs() {
        val gene = gene()
        gene.choose(1)
        gene.advance(1f, 2f)
        val before = weights(gene)
        gene.choose(2)
        assertEquals(before, weights(gene), "no option may jump when a second change lands: $before then ${weights(gene)}")
        var total = 0f
        repeat(10) {
            gene.advance(0.2f, 2f)
            total = weights(gene).sum()
            assertEquals(1f, total, 1e-5f, "the weights must add up to one")
        }
        assertEquals(listOf(0f, 0f, 1f), weights(gene))
    }

    @Test
    fun aRestartSettlesOnTheStartOption() {
        val gene = Genes(1L).choice("shape", 3, start = 2)
        gene.choose(0)
        gene.advance(0.5f, 2f)
        gene.restart()
        assertEquals(listOf(0f, 0f, 1f), weights(gene))
        gene.choose(1)
        assertTrue(gene.weight(2) == 1f && gene.weight(1) == 0f, "a change after a restart starts from the start option")
    }
}
