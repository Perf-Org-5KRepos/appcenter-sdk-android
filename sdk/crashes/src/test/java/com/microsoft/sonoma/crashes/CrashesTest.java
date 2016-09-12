package com.microsoft.sonoma.crashes;

import android.content.Context;
import android.os.SystemClock;

import com.microsoft.sonoma.core.Constants;
import com.microsoft.sonoma.core.channel.Channel;
import com.microsoft.sonoma.core.ingestion.models.Device;
import com.microsoft.sonoma.core.ingestion.models.Log;
import com.microsoft.sonoma.core.ingestion.models.json.LogFactory;
import com.microsoft.sonoma.core.ingestion.models.json.LogSerializer;
import com.microsoft.sonoma.core.utils.PrefStorageConstants;
import com.microsoft.sonoma.core.utils.SonomaLog;
import com.microsoft.sonoma.core.utils.StorageHelper;
import com.microsoft.sonoma.core.utils.UUIDUtils;
import com.microsoft.sonoma.crashes.ingestion.models.ManagedErrorLog;
import com.microsoft.sonoma.crashes.ingestion.models.json.ManagedErrorLogFactory;
import com.microsoft.sonoma.crashes.model.ErrorAttachment;
import com.microsoft.sonoma.crashes.model.ErrorReport;
import com.microsoft.sonoma.crashes.model.TestCrashException;
import com.microsoft.sonoma.crashes.utils.ErrorLogHelper;

import junit.framework.Assert;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyNoMoreInteractions;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.when;

@SuppressWarnings("unused")
@RunWith(PowerMockRunner.class)
@PrepareForTest({ErrorLogHelper.class, SystemClock.class, StorageHelper.InternalStorage.class, StorageHelper.PreferencesStorage.class, SonomaLog.class})
public class CrashesTest {

    @Rule
    private final TemporaryFolder errorStorageDirectory = new TemporaryFolder();

    private static void assertErrorEquals(ManagedErrorLog errorLog, Throwable throwable, ErrorReport report) {
        assertNotNull(report);
        assertEquals(errorLog.getId().toString(), report.getId());
        assertEquals(errorLog.getErrorThreadName(), report.getThreadName());
        assertEquals(throwable, report.getThrowable());
        assertEquals(errorLog.getToffset() - errorLog.getAppLaunchTOffset(), report.getAppStartTime().getTime());
        assertEquals(errorLog.getToffset(), report.getAppErrorTime().getTime());
        assertEquals(errorLog.getDevice(), report.getDevice());
    }

    @Before
    public void setUp() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        Crashes.unsetInstance();
        mockStatic(SystemClock.class);
        mockStatic(StorageHelper.InternalStorage.class);
        mockStatic(StorageHelper.PreferencesStorage.class);
        mockStatic(SonomaLog.class);
        when(SystemClock.elapsedRealtime()).thenReturn(System.currentTimeMillis());

        final String key = PrefStorageConstants.KEY_ENABLED + "_" + Crashes.getInstance().getGroupName();
        when(StorageHelper.PreferencesStorage.getBoolean(key, true)).thenReturn(true);

        /* Then simulate further changes to state. */
        PowerMockito.doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {

                /* Whenever the new state is persisted, make further calls return the new state. */
                boolean enabled = (Boolean) invocation.getArguments()[1];
                when(StorageHelper.PreferencesStorage.getBoolean(key, true)).thenReturn(enabled);
                return null;
            }
        }).when(StorageHelper.PreferencesStorage.class);
        StorageHelper.PreferencesStorage.putBoolean(eq(key), anyBoolean());
    }

    @Test
    public void singleton() {
        Assert.assertSame(Crashes.getInstance(), Crashes.getInstance());
    }

    @Test
    public void notInit() {

        /* Just check log is discarded without throwing any exception. */
        Crashes.notifyUserConfirmation(Crashes.SEND);

        verifyStatic(times(1));
        SonomaLog.error(eq(Crashes.LOG_TAG), anyString());
    }

    @Test
    public void checkConfig() {
        Crashes instance = Crashes.getInstance();
        Map<String, LogFactory> factories = instance.getLogFactories();
        assertNotNull(factories);
        assertTrue(factories.remove(ManagedErrorLog.TYPE) instanceof ManagedErrorLogFactory);
        assertTrue(factories.isEmpty());
        assertEquals(1, instance.getTriggerCount());
        assertEquals(Crashes.ERROR_GROUP, instance.getGroupName());
    }

    @Test
    public void setEnabled() {

        /* Setup mock. */
        mockStatic(ErrorLogHelper.class);
        File dir = mock(File.class);
        File file1 = mock(File.class);
        File file2 = mock(File.class);
        when(ErrorLogHelper.getErrorStorageDirectory()).thenReturn(dir);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{});
        when(dir.listFiles()).thenReturn(new File[]{file1, file2});
        Crashes.getInstance().onChannelReady(mock(Context.class), mock(Channel.class));

        /* Test. */
        assertTrue(Crashes.isEnabled());
        assertTrue(Crashes.getInstance().getInitializeTimestamp() > 0);
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() instanceof UncaughtExceptionHandler);
        Crashes.setEnabled(false);
        assertFalse(Crashes.isEnabled());
        assertEquals(Crashes.getInstance().getInitializeTimestamp(), -1);
        assertFalse(Thread.getDefaultUncaughtExceptionHandler() instanceof UncaughtExceptionHandler);
        assertFalse(verify(file1).delete());
        assertFalse(verify(file2).delete());
        Crashes.setEnabled(true);
        assertTrue(Crashes.isEnabled());
        assertTrue(Crashes.getInstance().getInitializeTimestamp() > 0);
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() instanceof UncaughtExceptionHandler);
    }

    @Test
    public void setEnabledWithoutContext() {
        Crashes crashes = Crashes.getInstance();
        crashes.setUncaughtExceptionHandler(null);
        crashes.setInstanceEnabled(true);
        assertNull(crashes.getUncaughtExceptionHandler());

        UncaughtExceptionHandler mockHandler = mock(UncaughtExceptionHandler.class);
        crashes.setUncaughtExceptionHandler(mockHandler);
        crashes.setInstanceEnabled(true);
        assertEquals(mockHandler, crashes.getUncaughtExceptionHandler());

        verifyNoMoreInteractions(mockHandler);
    }

    @Test
    public void queuePendingCrashesShouldProcess() throws IOException, ClassNotFoundException, JSONException {
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);

        final ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mockContext, Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);
        ErrorReport report = new ErrorReport();

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(new File("."));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report);
        when(StorageHelper.InternalStorage.read(any(File.class))).thenReturn("");
        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenReturn(new RuntimeException());

        ErrorAttachment mockAttachment = mock(ErrorAttachment.class);
        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(report)).thenReturn(true);
        when(mockListener.shouldAwaitUserConfirmation()).thenReturn(false);
        when(mockListener.getErrorAttachment(report)).thenReturn(mockAttachment);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString())).thenReturn(errorLog);

        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onChannelReady(mockContext, mockChannel);

        verify(mockListener).shouldProcess(report);
        verify(mockListener).shouldAwaitUserConfirmation();
        verify(mockListener).getErrorAttachment(report);
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {
            @Override
            public boolean matches(Object log) {
                return log.equals(errorLog);
            }
        }), eq(crashes.getGroupName()));
    }

    @Test
    public void queuePendingCrashesShouldNotProcess() throws IOException, ClassNotFoundException, JSONException {
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);

        final ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mockContext, Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);
        ErrorReport report = new ErrorReport();

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(new File("."));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report);
        when(StorageHelper.InternalStorage.read(any(File.class))).thenReturn("");
        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenReturn(new RuntimeException());

        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(report)).thenReturn(false);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString())).thenReturn(errorLog);

        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onChannelReady(mockContext, mockChannel);

        verify(mockListener).shouldProcess(report);
        verify(mockListener, never()).shouldAwaitUserConfirmation();
        verify(mockListener, never()).getErrorAttachment(report);
        verify(mockChannel, never()).enqueue(any(Log.class), eq(crashes.getGroupName()));
    }

    @Test
    public void queuePendingCrashesAlwaysSend() throws IOException, ClassNotFoundException, JSONException {
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);

        final ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mockContext, Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);
        ErrorReport report = new ErrorReport();

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(new File("."));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(report);
        when(StorageHelper.InternalStorage.read(any(File.class))).thenReturn("");
        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenReturn(new RuntimeException());
        when(StorageHelper.PreferencesStorage.getBoolean(eq(Crashes.PREF_KEY_ALWAYS_SEND), anyBoolean())).thenReturn(true);

        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(report)).thenReturn(true);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString())).thenReturn(errorLog);

        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onChannelReady(mockContext, mockChannel);

        verify(mockListener).shouldProcess(report);
        verify(mockListener, never()).shouldAwaitUserConfirmation();
        verify(mockListener).getErrorAttachment(report);
        verify(mockChannel).enqueue(argThat(new ArgumentMatcher<Log>() {
            @Override
            public boolean matches(Object log) {
                return log.equals(errorLog);
            }
        }), eq(crashes.getGroupName()));
    }

    @Test
    public void processPendingErrorsCorrupted() throws JSONException {
        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(StorageHelper.InternalStorage.read(any(File.class))).thenReturn("");

        Crashes crashes = Crashes.getInstance();

        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString())).thenReturn(mock(ManagedErrorLog.class));
        crashes.setLogSerializer(logSerializer);

        CrashesListener listener = mock(CrashesListener.class);
        crashes.setInstanceListener(listener);

        Channel channel = mock(Channel.class);
        crashes.onChannelReady(mock(Context.class), channel);
        verifyZeroInteractions(listener);
        verify(channel, never()).enqueue(any(Log.class), anyString());
    }

    @Test
    public void noQueueingWhenDisabled() {
        mockStatic(ErrorLogHelper.class);
        File dir = mock(File.class);
        when(ErrorLogHelper.getErrorStorageDirectory()).thenReturn(dir);
        when(dir.listFiles()).thenReturn(new File[]{});

        Crashes.setEnabled(false);
        Crashes crashes = Crashes.getInstance();

        crashes.onChannelReady(mock(Context.class), mock(Channel.class));

        verifyStatic();
        ErrorLogHelper.getErrorStorageDirectory();
        verifyNoMoreInteractions(ErrorLogHelper.class);
    }

    @Test
    public void noQueueNullLog() throws JSONException {
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString())).thenReturn(null);
        crashes.setLogSerializer(logSerializer);

        crashes.onChannelReady(mockContext, mockChannel);

        verify(mockChannel, never()).enqueue(any(Log.class), anyString());
    }

    @Test
    public void printErrorOnJSONException() throws JSONException {
        Context mockContext = mock(Context.class);
        Channel mockChannel = mock(Channel.class);
        final JSONException jsonException = new JSONException("Fake JSON exception");

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(StorageHelper.InternalStorage.read(any(File.class))).thenReturn("");
        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);

        when(logSerializer.deserializeLog(anyString())).thenThrow(jsonException);
        crashes.setLogSerializer(logSerializer);

        crashes.onChannelReady(mockContext, mockChannel);

        verify(mockChannel, never()).enqueue(any(Log.class), anyString());

        verifyStatic();
        SonomaLog.error(eq(Crashes.LOG_TAG), anyString(), eq(jsonException));
    }

    @Test(expected = TestCrashException.class)
    public void generateTestCrashInDebug() {
        Constants.APPLICATION_DEBUGGABLE = true;
        Crashes.generateTestCrash();
    }

    @Test
    public void generateTestCrashInRelease() {
        Constants.APPLICATION_DEBUGGABLE = false;
        Crashes.generateTestCrash();
    }

    @Test
    public void getChannelListener() throws IOException, ClassNotFoundException {
        final ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mock(Context.class), Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);
        final String exceptionMessage = "This is a test exception.";
        final Exception exception = new Exception() {
            @Override
            public String getMessage() {
                return exceptionMessage;
            }
        };

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(new File("."));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenCallRealMethod();

        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenReturn(exception);

        Crashes.setListener(new AbstractCrashesListener() {
            @Override
            public void onBeforeSending(ErrorReport report) {
                assertErrorEquals(errorLog, exception, report);
            }

            @Override
            public void onSendingSucceeded(ErrorReport report) {
                assertErrorEquals(errorLog, exception, report);
            }

            @Override
            public void onSendingFailed(ErrorReport report, Exception e) {
                assertErrorEquals(errorLog, exception, report);
            }
        });

        Channel.GroupListener listener = Crashes.getInstance().getChannelListener();
        listener.onBeforeSending(errorLog);
        listener.onSuccess(errorLog);
        listener.onFailure(errorLog, exception);
    }

    @Test
    public void getChannelListenerErrors() throws IOException, ClassNotFoundException {
        final ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mock(Context.class), Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(new File("."));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(null);

        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenReturn(null);

        CrashesListener mockListener = mock(CrashesListener.class);
        Crashes crashes = Crashes.getInstance();

        crashes.setInstanceListener(mockListener);

        Channel.GroupListener listener = Crashes.getInstance().getChannelListener();

        listener.onBeforeSending(errorLog);
        verifyStatic();
        SonomaLog.warn(eq(Crashes.LOG_TAG), anyString());
        Mockito.verifyNoMoreInteractions(mockListener);

        listener.onSuccess(mock(Log.class));
        verifyStatic();
        SonomaLog.warn(eq(Crashes.LOG_TAG), contains(Log.class.getName()));
        Mockito.verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void handleUserConfirmationDoNotSend() throws IOException, ClassNotFoundException, JSONException {
        final ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mock(Context.class), Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(new File("."));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(new ErrorReport());
        when(StorageHelper.InternalStorage.read(any(File.class))).thenReturn("");
        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenReturn(null);

        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(any(ErrorReport.class))).thenReturn(true);
        when(mockListener.shouldAwaitUserConfirmation()).thenReturn(true);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString())).thenReturn(errorLog);

        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onChannelReady(mock(Context.class), mock(Channel.class));

        Crashes.notifyUserConfirmation(Crashes.DONT_SEND);

        verify(mockListener, never()).getErrorAttachment(any(ErrorReport.class));

        verifyStatic();
        ErrorLogHelper.removeStoredErrorLogFile(errorLog.getId());
        verifyStatic();
        ErrorLogHelper.removeStoredThrowableFile(errorLog.getId());
    }

    @Test
    public void handleUserConfirmationAlwaysSend() throws IOException, ClassNotFoundException, JSONException {
        final ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mock(Context.class), Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(new File("."));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(null);

        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenReturn(null);

        CrashesListener mockListener = mock(CrashesListener.class);
        when(mockListener.shouldProcess(any(ErrorReport.class))).thenReturn(true);

        Crashes crashes = Crashes.getInstance();
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString())).thenReturn(errorLog);

        crashes.setLogSerializer(logSerializer);
        crashes.setInstanceListener(mockListener);
        crashes.onChannelReady(mock(Context.class), mock(Channel.class));

        Crashes.notifyUserConfirmation(Crashes.ALWAYS_SEND);

        verifyStatic();
        StorageHelper.PreferencesStorage.putBoolean(Crashes.PREF_KEY_ALWAYS_SEND, true);
    }

    @Test
    public void buildErrorReport() throws IOException, ClassNotFoundException {
        final ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mock(Context.class), Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);
        final String exceptionMessage = "This is a test exception.";
        final Exception exception = new Exception() {
            @Override
            public String getMessage() {
                return exceptionMessage;
            }
        };

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(new File(".")).thenReturn(null);
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenCallRealMethod();

        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenReturn(exception);

        Crashes crashes = Crashes.getInstance();
        ErrorReport report = crashes.buildErrorReport(errorLog);
        assertErrorEquals(errorLog, exception, report);

        errorLog.setId(UUIDUtils.randomUUID());
        report = crashes.buildErrorReport(errorLog);
        assertNull(report);
    }

    @Test
    public void buildErrorReportError() throws IOException, ClassNotFoundException {
        final ManagedErrorLog errorLog = ErrorLogHelper.createErrorLog(mock(Context.class), Thread.currentThread(), new RuntimeException(), Thread.getAllStackTraces(), 0);

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{new File(".")});
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(new File("."));
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenReturn(null);

        Exception classNotFoundException = mock(ClassNotFoundException.class);
        Exception ioException = mock(IOException.class);
        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenThrow(classNotFoundException).thenThrow(ioException);

        Crashes crashes = Crashes.getInstance();

        ErrorReport report = crashes.buildErrorReport(errorLog);
        assertNull(report);
        report = crashes.buildErrorReport(errorLog);
        assertNull(report);

        verifyStatic();
        SonomaLog.error(eq(Crashes.LOG_TAG), anyString(), eq(classNotFoundException));
        verifyStatic();
        SonomaLog.error(eq(Crashes.LOG_TAG), anyString(), eq(ioException));
    }

    @Test
    public void defaultErrorReportingListener() {
        Crashes crashes = Crashes.getInstance();
        CrashesListener defaultListener = crashes.getInstanceListener();
        crashes.setInstanceListener(new CrashesListener() {
            @Override
            public boolean shouldProcess(ErrorReport report) {
                return false;
            }

            @Override
            public boolean shouldAwaitUserConfirmation() {
                return false;
            }

            @Override
            public ErrorAttachment getErrorAttachment(ErrorReport report) {
                return null;
            }

            @Override
            public void onBeforeSending(ErrorReport report) {
            }

            @Override
            public void onSendingFailed(ErrorReport report, Exception e) {
            }

            @Override
            public void onSendingSucceeded(ErrorReport report) {
            }
        });

        /* Verify crashes has default listener when null is assigned. */
        crashes.setInstanceListener(null);
        CrashesListener listener = crashes.getInstanceListener();
        assertEquals(defaultListener, listener);

        /* Verify default behavior. */
        assertTrue(defaultListener.shouldProcess(null));
        assertFalse(defaultListener.shouldAwaitUserConfirmation());

        /* Nothing to verify. */
        defaultListener.getErrorAttachment(null);
        defaultListener.onBeforeSending(null);
        defaultListener.onSendingSucceeded(null);
        defaultListener.onSendingFailed(null, null);
    }

    @Test
    public void crashInLastSession() throws JSONException, IOException, ClassNotFoundException {
        int tOffset = 10;
        long appLaunchTOffset = 100L;

        ManagedErrorLog errorLog = new ManagedErrorLog();
        errorLog.setId(UUIDUtils.randomUUID());
        errorLog.setErrorThreadName(Thread.currentThread().getName());
        errorLog.setToffset(tOffset);

        errorLog.setAppLaunchTOffset(appLaunchTOffset);
        errorLog.setDevice(mock(Device.class));

        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString())).thenReturn(errorLog);

        Throwable throwable = mock(Throwable.class);

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(errorStorageDirectory.newFile("last-error-log.json"));
        when(ErrorLogHelper.getStoredThrowableFile(any(UUID.class))).thenReturn(errorStorageDirectory.newFile());
        when(ErrorLogHelper.getErrorReportFromErrorLog(any(ManagedErrorLog.class), any(Throwable.class))).thenCallRealMethod();
        when(StorageHelper.InternalStorage.read(any(File.class))).thenReturn("");
        when(StorageHelper.InternalStorage.readObject(any(File.class))).thenReturn(throwable);

        Crashes.getInstance().setLogSerializer(logSerializer);

        assertFalse(Crashes.hasCrashedInLastSession());
        assertNull(Crashes.getLastSessionCrashReport());

        // Last session error is only fetched upon initialization (triggered by setting the module to enabled)
        Crashes.setEnabled(true);

        assertTrue(Crashes.hasCrashedInLastSession());
        ErrorReport report = Crashes.getLastSessionCrashReport();
        assertNotNull(report);
        assertEquals(errorLog.getId().toString(), report.getId());
        assertEquals(errorLog.getErrorThreadName(), report.getThreadName());
        assertEquals(new Date(tOffset - appLaunchTOffset), report.getAppStartTime());
        assertEquals(new Date(tOffset), report.getAppErrorTime());
        assertNotNull(report.getDevice());
        assertEquals(throwable, report.getThrowable());
    }

    @Test
    public void noCrashInLastSessionWhenDisabled() {

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getErrorStorageDirectory()).thenReturn(errorStorageDirectory.getRoot());

        Crashes.setEnabled(false);

        assertFalse(Crashes.hasCrashedInLastSession());
        assertNull(Crashes.getLastSessionCrashReport());

        verifyStatic(never());
        ErrorLogHelper.getLastErrorLogFile();
    }

    @Test
    public void crashInLastSessionError() throws JSONException, IOException, ClassNotFoundException {
        LogSerializer logSerializer = mock(LogSerializer.class);
        when(logSerializer.deserializeLog(anyString())).thenReturn(mock(ManagedErrorLog.class));

        mockStatic(ErrorLogHelper.class);
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(errorStorageDirectory.newFile("last-error-log.json"));
        when(StorageHelper.InternalStorage.read(any(File.class))).thenReturn("");

        Crashes.getInstance().setLogSerializer(logSerializer);

        assertFalse(Crashes.hasCrashedInLastSession());
        assertNull(Crashes.getLastSessionCrashReport());

        // Last session error is only fetched upon initialization (triggered by setting the module to enabled)
        Crashes.setEnabled(true);

        assertFalse(Crashes.hasCrashedInLastSession());
        assertNull(Crashes.getLastSessionCrashReport());

        JSONException jsonException = new JSONException("Fake JSON exception");
        when(logSerializer.deserializeLog(anyString())).thenThrow(jsonException);

        Crashes.setEnabled(true);

        assertFalse(Crashes.hasCrashedInLastSession());
        assertNull(Crashes.getLastSessionCrashReport());

        verifyStatic();
        SonomaLog.error(eq(Crashes.LOG_TAG), anyString(), eq(jsonException));
    }

    @Test
    public void crashInLastSessionCorrupted() throws IOException {
        mockStatic(ErrorLogHelper.class);
        File file = errorStorageDirectory.newFile("last-error-log.json");
        when(ErrorLogHelper.getStoredErrorLogFiles()).thenReturn(new File[]{file});
        when(ErrorLogHelper.getLastErrorLogFile()).thenReturn(file);
        Crashes.getInstance().onChannelReady(mock(Context.class), mock(Channel.class));
        assertFalse(Crashes.hasCrashedInLastSession());
        assertNull(Crashes.getLastSessionCrashReport());
    }
}