import CoreMedia

func labHostTime() -> Double { CMTimeGetSeconds(CMClockGetTime(CMClockGetHostTimeClock())) }
