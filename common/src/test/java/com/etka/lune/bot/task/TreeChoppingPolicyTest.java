package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeChoppingPolicyTest {

    @Test
    void contextsSeparateTreeSizeToolAndApproach() {
        assertEquals("size=small;tool=hand;approach=near",
                TreeChoppingPolicy.phase(5, 1.0F, 3.0));
        assertEquals("size=giant;tool=fast-tool;approach=far",
                TreeChoppingPolicy.phase(30, 8.0F, 20.0));
    }

    @Test
    void tacticsProduceDifferentSafeCandidateOrders() {
        double trunkOnCore = score(TreeChoppingPolicy.TRUNK_FIRST, 0, 1, 0);
        double trunkOnBranch = score(TreeChoppingPolicy.TRUNK_FIRST, 3, 1, 0);
        assertTrue(trunkOnCore < trunkOnBranch);

        double outerOnCore = score(TreeChoppingPolicy.OUTER_FIRST, 0, 1, 0);
        double outerOnBranch = score(TreeChoppingPolicy.OUTER_FIRST, 3, 1, 0);
        assertTrue(outerOnBranch < outerOnCore);
    }

    private static double score(String action, int x, int y, int z) {
        return TreeChoppingPolicy.candidateScore(action,
                0, 0, 0,
                0, 0, 0,
                x, y, z,
                9);
    }
}
