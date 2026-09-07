// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.project

import org.mcuhome.ui.api.OptionKind
import org.mcuhome.ui.api.OptionOrigin
import org.mcuhome.ui.api.ProjectOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun option(
    origin: OptionOrigin,
    value: String? = "something",
    editable: Boolean = true,
) = ProjectOption(
    name = "build.mode",
    label = "build.mode",
    help = "how a local build is executed",
    kind = OptionKind.Text,
    value = value,
    origin = origin,
    editable = editable,
)

class ProjectOptionActionTest {
    @Test
    fun whatTheProjectSetItCanTakeBack() {
        assertEquals(OptionAction.Reset, optionAction(option(OptionOrigin.Project)))
        assertEquals("reset", OptionAction.Reset.label)
    }

    @Test
    fun whatAnotherLayerSetCanBeOverridden() {
        assertEquals(OptionAction.Override, optionAction(option(OptionOrigin.User)))
        assertEquals(OptionAction.Override, optionAction(option(OptionOrigin.System)))
        assertEquals(OptionAction.Override, optionAction(option(OptionOrigin.Default)))
        assertEquals("override", OptionAction.Override.label)
    }

    @Test
    fun anOptionWithNoValueAnywhereHasNothingToPromote() {
        assertEquals(OptionAction.None, optionAction(option(OptionOrigin.Default, value = null)))
    }

    @Test
    fun anOptionNoFileMaySetOffersNothing() {
        assertEquals(OptionAction.None, optionAction(option(OptionOrigin.Project, editable = false)))
    }
}

class ProjectOptionOriginTest {
    @Test
    fun theLayerIsNamedAsTheConfigurationNamesIt() {
        assertEquals("project", originLabel(option(OptionOrigin.Project)))
        assertEquals("user", originLabel(option(OptionOrigin.User)))
        assertEquals("default", originLabel(option(OptionOrigin.Default)))
    }

    @Test
    fun anOptionWithNoValueIsSetByNobody() {
        assertEquals("—", originLabel(option(OptionOrigin.Default, value = null)))
    }

    @Test
    fun onlyAValueTheProjectCarriesIsMarkedAsItsOwn() {
        assertTrue(isProjectValue(option(OptionOrigin.Project)))
        assertFalse(isProjectValue(option(OptionOrigin.User)))
        assertFalse(isProjectValue(option(OptionOrigin.Project, value = null)))
    }

    @Test
    fun theFourTabsAreTheFourTheDesignNames() {
        assertEquals(
            listOf("Options", "Edit as YAML", "Boards", "Doctor"),
            ProjectTab.entries.map { it.label },
        )
    }
}
