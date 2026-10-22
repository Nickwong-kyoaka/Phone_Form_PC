package com.phoneform.desktop

import org.junit.Assert.*
import org.junit.Test

class ScopedSaveActionsTest {
    @Test fun closingNestedEditorRestoresTheStillMountedParent() {
        val registry=ScopedSaveActions();val parent=Any();val child=Any();val calls=mutableListOf<String>()
        registry.register(parent){calls+="parent"};registry.register(child){calls+="child"}
        registry.action!!.invoke();registry.unregister(child);registry.action!!.invoke()
        assertEquals(listOf("child","parent"),calls);assertSame(parent,registry.owner)
        registry.unregister(parent);assertNull(registry.action)
    }

    @Test fun disposedParentCannotBeResurrectedWhenItsChildCloses() {
        val registry=ScopedSaveActions();val parent=Any();val child=Any();var staleCalls=0
        registry.register(parent){staleCalls++};registry.register(child){}
        registry.unregister(parent);assertSame(child,registry.owner)
        registry.unregister(child);assertNull(registry.action);assertEquals(0,staleCalls)
    }

    @Test fun changingParentCallbackDoesNotStealMountedModalPriority() {
        val registry=ScopedSaveActions();val parent=Any();val child=Any();var saved=""
        registry.register(parent){saved="old"};registry.register(child){saved="child"}
        registry.register(parent){saved="new"};registry.action!!.invoke();assertEquals("child",saved)
        registry.unregister(child);registry.action!!.invoke();assertEquals("new",saved)
    }
}
