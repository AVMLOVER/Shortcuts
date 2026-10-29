package com.germanium.shortcuts.core

import org.junit.Assert.*
import org.junit.Test

class Technical104Test {
    @Test fun landscapePhonesKeepTheirExistingLayout() {
        val phone=InterfacePolicies.adaptive(900,400,400,false)
        assertFalse(phone.largeDevice);assertFalse(phone.sidePane);assertFalse(phone.navigationRail)
    }
    @Test fun tabletsUseOneThirdAndOnlyLandscapeMovesNavigation() {
        val portrait=InterfacePolicies.adaptive(800,1280,800,false)
        val landscape=InterfacePolicies.adaptive(1280,800,800,false)
        assertTrue(portrait.sidePane);assertFalse(portrait.navigationRail);assertEquals(1f/3f,portrait.paneFraction,0.0001f)
        assertTrue(landscape.sidePane);assertTrue(landscape.navigationRail)
    }
    @Test fun foldsUseHalfWhenExpandedAndFallBackOnTheCoverScreen() {
        val expanded=InterfacePolicies.adaptive(1000,840,840,true)
        val cover=InterfacePolicies.adaptive(412,915,412,true)
        assertTrue(expanded.sidePane);assertTrue(expanded.navigationRail);assertEquals(0.5f,expanded.paneFraction,0f)
        assertTrue(cover.largeDevice);assertFalse(cover.sidePane);assertFalse(cover.navigationRail)
    }
    @Test fun aNarrowTabletWindowKeepsThePreferenceButDoesNotCrampTheEditor() {
        val narrow=InterfacePolicies.adaptive(500,900,800,false)
        assertTrue(narrow.largeDevice);assertFalse(narrow.sidePane);assertFalse(narrow.navigationRail)
        assertFalse(InterfacePolicies.adaptive(599,1000,800,false).sidePane)
        assertTrue(InterfacePolicies.adaptive(600,1000,800,false).sidePane)
    }
    @Test fun variableActionsHaveNoMoreAndOnlyShowResultMovesItsCancellationOption() {
        for(type in listOf("create_variable","set_variable","get_variable","append_variable")){
            assertFalse(InterfacePolicies.variableOptions(type));assertFalse(InterfacePolicies.hasMore(type))
        }
        assertTrue(InterfacePolicies.variableOptions("text"));assertTrue(InterfacePolicies.hasMore("text"))
        assertTrue(InterfacePolicies.hasMore("show_result"));assertFalse(InterfacePolicies.variableOptions("show_result"))
        assertFalse(InterfacePolicies.hasMore("notification"));assertFalse(InterfacePolicies.hasMore("quick_look"))
    }
    @Test fun anExplicitWidgetPresentationSuppressesBothQuickLookAndDefaultOutput() {
        val quick=Value.Text("image.png");val result=RunResult(RunStatus.SUCCEEDED,emptyList(),finalValue=Value.Text("default"))
        assertNull(InterfacePolicies.widgetResult(true,quick,result,null,null))
        assertNull(InterfacePolicies.widgetResult(true,null,result,null,"stopped"))
    }
    @Test fun aWidgetUsesTheLastTypedQuickLookOrTheTypedDefaultResult() {
        val quick=Value.ListValue(listOf(Value.Text("one.png"),Value.Text("two.pdf")))
        val value=Value.Dictionary(mapOf("answer" to Value.Number(42.toBigDecimal())))
        val result=RunResult(RunStatus.SUCCEEDED,emptyList(),finalValue=value)
        assertEquals(quick,InterfacePolicies.widgetResult(false,quick,result,null,null))
        assertEquals(value,InterfacePolicies.widgetResult(false,null,result,null,null))
    }
    @Test fun aLaterFailureRemainsVisibleEvenIfAnEarlierDialogOrNotificationSucceeded() {
        assertEquals(Value.Text("failure"),InterfacePolicies.widgetResult(true,Value.Text("preview"),null,"failure",null))
        assertEquals(Value.Text("stopped"),InterfacePolicies.widgetResult(false,null,null,null,"stopped"))
    }
}
