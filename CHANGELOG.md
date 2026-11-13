# Changelog

## v2.0
- More adapters: Bluetooth Low Energy and Bluetooth Classic (SPP), for Vgate iCar Pro, OBDLink, Veepeak, BAFX, generic ELM327 and more. Classic adapters must be paired in Android settings first.
- BlueDriver, FIXD and Carista are detected by name, shown as "Not supported", with a link to their own app.
- 2001 Chevy S-10 LS profile: J1850 VPW (tries ATSP2, falls back to automatic), GM Mode 22 sensors (trans temp, oil pressure, knock retard, misfire counts, injector pulse width, commanded gear, TCC slip, A/C pressure). Unsupported sensors are skipped automatically.
- New demo vehicle: 2001 S-10 LS, running through the real command parser. The Honda Accord demo is still there.
- VIN auto-detect: reads the VIN, matches it to a saved vehicle, or asks before creating a new profile and filling in year, make, model, engine, fuel, drive and transmission (NHTSA lookup with an offline fallback). Manual VIN entry and a Decode VIN button on the Profile tab.
- Live mileage: uses the car's odometer when it reports one, otherwise adds up speed. Type your real reading to correct it.
- Maintenance: default intervals (oil, transmission fluid and filter, air filter, spark plugs, brakes, tire rotation), service log with date, mileage, notes and cost, editable and custom intervals, Good / Due soon / Overdue status, and an in-app banner when one is crossed.

## v1.9
- New OBD tabs: Smog (readiness), Health (baseline), Trips (drive log), Split (now playing + 2 gauges).
- Watch repair videos button on trouble codes.
- Overheat alert with beeps; low-voltage beeps.
- Synced lyrics on the Playing tab (LRCLIB, optional).
- Multiple vehicles and service reminders in Profile.
- Gauge layouts can be copied and pasted between phones.
- Welcome screen and Spotify setup guide.

## v1.8
- Icon-only side menu that scales to any screen, clock in the menu, quiet update prompt (Install now / Later), car-screen system-bar fix.

## v1.7
- In-app updater and the Feedback button.

## v1.6
- First public release.
