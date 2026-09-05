package net.sjrx.intellij.plugins.avecmoi

import java.util.concurrent.atomic.AtomicInteger

/**
 * Ring mutations must publish on [AvecMoiRingService.TOPIC] — that's the only signal the tool window
 * has to refresh. A new mutation method that forgets to fire it would leave the UI silently stale, so
 * this pins the contract: state-changing ops notify, no-op ops don't.
 */
class AvecMoiRingNotificationTest : AbstractAvecMoiTest() {

  private val notifications = AtomicInteger(0)

  override fun setUp() {
    super.setUp()
    project.messageBus.connect(testRootDisposable)
      .subscribe(AvecMoiRingService.TOPIC, AvecMoiRingListener { notifications.incrementAndGet() })
  }

  /** Run [block] and return how many ring-changed notifications it produced. */
  private fun notificationsFrom(block: () -> Unit): Int {
    val before = notifications.get()
    block()
    return notifications.get() - before
  }

  fun testEveryHandoffMutationNotifiesListeners() {
    assertEquals("set", 1, notificationsFrom { ring.set(listOf(spec(note = "a"))) })
    assertEquals("add", 1, notificationsFrom { ring.add(spec(note = "b"), null) })
    assertEquals("markDone", 1, notificationsFrom { ring.markDone(0, true) })
    assertEquals("remove", 1, notificationsFrom { ring.remove(0) })
    assertEquals("clear", 1, notificationsFrom { ring.clear() })
  }

  fun testEverySessionMutationNotifiesListeners() {
    val id = ring.createSession("First", null) // used below
    // createSession itself fired once already; measure the rest.
    assertEquals("createSession", 1, notificationsFrom { ring.createSession("Second", null) })
    assertEquals("switchSession", 1, notificationsFrom { ring.switchSession(id) })
    assertEquals("renameSession", 1, notificationsFrom { ring.renameSession(id, "Renamed") })
    assertEquals("completeSession", 1, notificationsFrom { ring.completeSession(id) })
    assertEquals("deleteSession", 1, notificationsFrom { ring.deleteSession(id) })
  }

  fun testAFailedMutationDoesNotNotify() {
    // Fixture Setup
    ring.set(listOf(spec(note = "only")))

    // Verification — operations that change nothing must stay silent (no needless UI churn)
    assertEquals("out-of-range markDone", 0, notificationsFrom { ring.markDone(9, true) })
    assertEquals("out-of-range remove", 0, notificationsFrom { ring.remove(9) })
    assertEquals("switch to unknown session", 0, notificationsFrom { ring.switchSession("nope") })
    assertEquals("rename unknown session", 0, notificationsFrom { ring.renameSession("nope", "x") })
    assertEquals("delete unknown session", 0, notificationsFrom { ring.deleteSession("nope") })
  }
}
