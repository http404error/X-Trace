//package edu.berkeley.xtrace;
//
//import junit.framework.TestCase;
//
//import org.junit.Test;
//
//public class XTraceTest extends TestCase {
//	
//	@Override
//	public void setUp() {
//		XTraceContext.doClearThreadContext();
//	}
//
//	@Test
//	public void testSingleContext() {
//		// null context
//		XTraceContext.doSetThreadContext((XTraceMetadata) null);
//		assertNull(XTraceContext.getThreadContext());
//		
//		// invalid context
//		XTraceContext.doSetThreadContext(new XTraceMetadata());
//		assertNull(XTraceContext.getThreadContext());
//		
//		// valid context 1
//		TaskID task = new TaskID(4);
//		XTraceContext.doSetThreadContext(new XTraceMetadata(task, 1234));
//		assertNotNull(XTraceContext.getThreadContext());
//		assertEquals(1, XTraceContext.getThreadContext().size());
//		assertEquals(new XTraceMetadata(task, 1234), XTraceContext.getThreadContext().iterator().next());
//		
//		// valid context 2
//		TaskID task2 = new TaskID(20);
//		XTraceContext.doSetThreadContext(new XTraceMetadata(task2, (long) 98769876));
//		assertNotNull(XTraceContext.getThreadContext());
//		assertEquals(1, XTraceContext.getThreadContext().size());
//		assertEquals(new XTraceMetadata(task2, (long) 98769876), XTraceContext.getThreadContext().iterator().next());
//	}
//
//	@Test
//	public void testMultiContext() {		
//		// null context 1
//		XTraceContext.doSetThreadContext((XTraceMetadata) null);
//		assertNull(XTraceContext.getThreadContext());
//		
//		// valid context 1
//		TaskID task = new TaskID(4);
//		XTraceContext.doSetThreadContext(new XTraceMetadata(task, 1234));
//		assertNotNull(XTraceContext.getThreadContext());
//		assertEquals(1, XTraceContext.getThreadContext().size());
//		assertEquals(new XTraceMetadata(task, 1234), XTraceContext.getThreadContext().iterator().next());
//		assertTrue(XTraceContext.getThreadContext().contains(new XTraceMetadata(task, 1234)));
//		
//		// valid context 2
//		XTraceContext.doJoinContext(new XTraceMetadata(task, (long) 98769876));
//		assertNotNull(XTraceContext.getThreadContext());
//		assertEquals(2, XTraceContext.getThreadContext().size());
//		assertTrue(XTraceContext.getThreadContext().contains(new XTraceMetadata(task, (long) 98769876)));
//
//		// valid context 3
//		TaskID task2 = new TaskID(20);
//		XTraceContext.doSetThreadContext(new XTraceMetadata(task2, (long) 123422));
//		assertNotNull(XTraceContext.getThreadContext());
//		assertEquals(1, XTraceContext.getThreadContext().size());
//		assertEquals(new XTraceMetadata(task2, (long) 123422), XTraceContext.getThreadContext().iterator().next());		
//		assertTrue(XTraceContext.getThreadContext().contains(new XTraceMetadata(task2, (long) 123422)));
//		assertFalse(XTraceContext.getThreadContext().contains(new XTraceMetadata(task, (long) 98769876)));
//		assertFalse(XTraceContext.getThreadContext().contains(new XTraceMetadata(task, (long) 1234)));
//	}
//
//	@Test
//	public void testClearContext() {
//		XTraceContext.doSetThreadContext(new XTraceMetadata(new TaskID(12), 1234));
//		assertNotNull(XTraceContext.getThreadContext());
//		assertEquals(1, XTraceContext.getThreadContext().size());
//		XTraceContext.doClearThreadContext();
//		assertNull(XTraceContext.getThreadContext());
//	}
//
//	@Test
//	public void testLogEvent() {
//		//fail("Not yet implemented"); // TODO
//	}
//
//	@Test
//	public void testCreateEvent() {
//		//fail("Not yet implemented"); // TODO
//	}
//	
//	@Test
//	public void testLogMerge() {
//		//fail("Not yet implemented"); // TODO		
//	}
//
//	@Test
//	public void testIsContextValid() {
//		XTraceContext.doClearThreadContext();
//		assertFalse(XTraceContext.isValid());
//		XTraceContext.doSetThreadContext(new XTraceMetadata(new TaskID(4), 1234));
//		assertTrue(XTraceContext.isValid());
//	}
//
//	@Test
//	public void testStartProcess() {
//		//fail("Not yet implemented"); // TODO
//	}
//
//}
