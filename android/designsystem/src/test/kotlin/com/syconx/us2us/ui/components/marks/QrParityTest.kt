package com.syconx.us2us.ui.components.marks

import com.syconx.us2us.ui.components.QrModules
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The QR codes are module for module the ones iOS draws (Core Image's segmentation and mask): the
 * contact code of 4.1 / 4.2 / 7.1 › “Kodumu göster”, read off the simulator's capture.
 */
class QrParityTest {
    @Test
    fun theOwnContactCodeIsTheOneIosDraws() {
        val modules = checkNotNull(QrModules.of("us2us:@selin_n#8a95e3f5cc758424b5e14c50e96acd0b"))
        assertEquals(IOS_CONTACT_CODE.size, modules.size)
        val drawn = (0 until modules.size).map { y -> (0 until modules.size).joinToString("") { x -> if (modules[y, x]) "#" else "." } }
        assertEquals(IOS_CONTACT_CODE.joinToString("\n"), drawn.joinToString("\n"))
    }

    private companion object {
        /** `android/build/ios-reference/dark-tr/chats_4.1-mycode.png`, sampled by zxing's detector. */
        val IOS_CONTACT_CODE = listOf(
            "#######..##...##..####.#..#######",
            "#.....#...##...#..#.##....#.....#",
            "#.###.#.##....#....#...#..#.###.#",
            "#.###.#.##........#..###..#.###.#",
            "#.###.#.#.##..###...#...#.#.###.#",
            "#.....#.####.....#.#..##..#.....#",
            "#######.#.#.#.#.#.#.#.#.#.#######",
            "........#.###...#.####...........",
            "#.#####..####.#..####..#..#####..",
            "....##.#...###..##.#.#...##...##.",
            ".#..###...#..###.#..##..#..#.#..#",
            "..#.#.....##.#..###.#.##.#.#..##.",
            "#######.#.#...###...##.##.##.#.#.",
            "##.....#....#..#..#..#..##..#####",
            "....#.#.#..#..###...#..#...#..##.",
            "#.###..##...####.....##..##..#.#.",
            "###..#############......##.#..##.",
            "#.#..#.###..#.#.#.###.##..#...##.",
            "...##.#.#....#....#.##...#.##..##",
            "#.##.#....##.##.....##..#..####..",
            ".#######..........#..#.####..#..#",
            "##.##...#.#..#.##...#...#.#.#####",
            "#.#####.##.#.##..###.#.#.#.#..##.",
            "#.#.##.#.##.##..#..###..##..##.#.",
            "#..####.#.#.....##.##.#########.#",
            "........#.#..#.#.###....#...###..",
            "#######...####.#.#..#.###.#.###.#",
            "#.....#.##.##.##.##....##...####.",
            "#.###.#.##..##.##..####.######...",
            "#.###.#.#.##..##..#..#..##..#.##.",
            "#.###.#.#.####.####.##.#..##.##..",
            "#.....#..#.#####.....#####..#....",
            "#######.#.#..####.#.#..#...#..##.",
        )
    }
}
