package edu.kit.ifv.mobitopp.simulation;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.time.SimpleTime;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

/** Checks the unchanged KIT strategy, not a SyntheSis replacement. */
class KitReschedulingTest {
    @Test void sameDayDelayDoesNotAutomaticallyShortenOrDropActivities() {
        var activity = mock(ActivityIfc.class);
        var schedule = mock(ModifiableActivitySchedule.class);
        when(activity.calculatePlannedEndDate()).thenReturn(SimpleTime.ofHours(16));
        new ReschedulingSkipTillHome(SimulationDays.containing(7)).adjustSchedule(
            schedule, activity, SimpleTime.ofHours(8), SimpleTime.ofHours(9));
        verify(activity, never()).changeDuration(anyInt());
        verifyNoInteractions(schedule);
    }
    @Test void arrivalOnNextDayDropsIntermediateActivitiesAndFitsRemainingDuration() {
        var activity = mock(ActivityIfc.class);
        var shopping = mock(ActivityIfc.class);
        var home = mock(ActivityIfc.class);
        var schedule = mock(ModifiableActivitySchedule.class);
        var arrival = SimpleTime.ofHours(26);
        when(activity.activityType()).thenReturn(ActivityType.WORK);
        when(activity.startDate()).thenReturn(arrival);
        when(activity.calculatePlannedEndDate()).thenReturn(SimpleTime.ofHours(30));
        when(home.startDate()).thenReturn(SimpleTime.ofHours(32));
        when(home.observedTripDuration()).thenReturn(30);
        when(schedule.nextHomeActivity(activity)).thenReturn(home);
        when(schedule.getSucceedingActivitiesUntilDate(activity, home.startDate())).thenReturn(List.of(shopping));
        when(schedule.hasNextActivity(activity)).thenReturn(true);
        when(schedule.nextActivity(activity)).thenReturn(home);
        new ReschedulingSkipTillHome(SimulationDays.containing(7)).adjustSchedule(
            schedule, activity, SimpleTime.ofHours(22), arrival);
        verify(schedule).removeActivity(shopping);
        verify(activity).changeDuration(330);
    }
    @Test void lateHomeArrivalShortensOvernightStayToPreserveNextPlannedDeparture() {
        var home = mock(ActivityIfc.class);
        var work = mock(ActivityIfc.class);
        var schedule = mock(ModifiableActivitySchedule.class);
        var arrival = SimpleTime.ofMinutes(18 * 60 + 30);
        when(home.activityType()).thenReturn(ActivityType.HOME);
        when(home.startDate()).thenReturn(arrival);
        when(home.calculatePlannedEndDate()).thenReturn(SimpleTime.ofHours(32));
        when(work.startDate()).thenReturn(SimpleTime.ofHours(32));
        when(work.observedTripDuration()).thenReturn(30);
        when(schedule.getSucceedingActivitiesUntilDate(home, arrival)).thenReturn(List.of());
        when(schedule.hasNextActivity(home)).thenReturn(true);
        when(schedule.nextActivity(home)).thenReturn(work);
        new ReschedulingSkipTillHome(SimulationDays.containing(7)).adjustSchedule(
                schedule, home, SimpleTime.ofHours(18), arrival);
        verify(home).changeDuration(13 * 60);
        verify(schedule, never()).removeActivity(any());
    }

}
