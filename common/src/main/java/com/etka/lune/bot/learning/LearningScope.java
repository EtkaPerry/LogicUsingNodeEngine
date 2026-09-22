package com.etka.lune.bot.learning;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rows of the learned profile a task writes to, as far as its parameters alone can say.
 *
 * <p>{@link LearningContext} names one row exactly, and it can only be built once the bot is
 * standing in a world: the dimension, the tool in hand and how far away the work is are all part
 * of the key. The editor has no world and still wants to put on a card what that card's job has
 * measured. So this is the half of the key a card fixes by itself - the skill, and every phase
 * entry that comes straight from a parameter - and {@link #matches(String)} is the question
 * "would a task with these parameters have written this row?".</p>
 *
 * <p>A scope with no fixed entries covers every row of its skill, which is right for a job whose
 * whole phase is decided at run time: tree chopping is keyed on the tree's size, the tool and the
 * approach, none of which a card knows before the bot is under the tree.</p>
 *
 * @param skill   the second segment of the key - {@code tree-chopping}, {@code block-mining} -
 *                or the task's {@link com.etka.lune.bot.Task#learningId()} for a job that
 *                measures under its own name
 * @param unitKey language key for one unit of the work ({@code lune.unit.logs}), or {@code null}
 *                for a job that only has a duration to report; its stem is what a row is keyed
 *                on ({@code unit=logs}), see {@link #unitId()}
 * @param fixed   phase entries in {@code name=value} form, every one of which a matching row
 *                must carry; their order does not matter
 */
public record LearningScope(String skill, String unitKey, List<String> fixed) {

    public LearningScope {
        skill = LearningContext.clean(skill);
        unitKey = unitKey == null || unitKey.isBlank() ? null : unitKey;
        fixed = fixed == null ? List.of() : List.copyOf(fixed);
    }

    /** Every row of one skill, whatever its phase. */
    public static LearningScope of(String skill) {
        return new LearningScope(skill, null, List.of());
    }

    /** The rows of one skill whose phase carries every one of {@code fixed}. */
    public static LearningScope of(String skill, String unitKey, String... fixed) {
        return new LearningScope(skill, unitKey, List.of(fixed));
    }

    /**
     * The unit as the learner writes it: the stem of the language key, so {@code lune.unit.logs}
     * is {@code logs} whatever language the player reads in, and {@code work} for a scope that
     * names no unit.
     *
     * <p>The stems are the English words on purpose. Rows were keyed on the rendered unit before
     * this existed, so every row written in English already carries its identifier; a row written
     * under another language's word is renamed when the profile is read.</p>
     */
    public String unitId() {
        return unitId(unitKey);
    }

    /** {@link #unitId()} for a key on its own; {@code null} or blank is {@code work}. */
    public static String unitId(String unitKey) {
        if (unitKey == null || unitKey.isBlank()) {
            return "work";
        }
        return unitKey.substring(unitKey.lastIndexOf('.') + 1);
    }

    /** True when a task with these parameters would have measured into the row {@code key}. */
    public boolean matches(String key) {
        return matches(LearningContext.parse(key));
    }

    public boolean matches(LearningContext context) {
        if (context == null || !context.task().equals(skill)) {
            return false;
        }
        if (fixed.isEmpty()) {
            return true;
        }
        Set<String> entries = new HashSet<>(List.of(context.phase().split(";")));
        return entries.containsAll(fixed);
    }
}
