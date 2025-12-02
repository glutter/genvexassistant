Instruction to controll RPM according to humidity level delta
- [x] monitor humidity level delta over time
    - [x] pull and store every 30 seconds
    - [x] retention period 14 days
    - [x] in postgres db
- [x] Activate boost when certain humidity
    - [x] activate boost function if humidity increases rapidly (shower)
    - [x] deactivate after 15 minutes again if humididy is not increasing anymore
    - [x] configurable functionality (should be able to turn this feature of)
- [x] General monitoring of humidity
    - [x] Depending on humidity level increase/decrease RPM (find recomended values for a normal 1 plan house)
    - [x] from 11pm - 6:30am lower speed to 1
- [] Create a monitor dashoard with nice graphs
    - [] Follow humidity trent vs RPM
    - [] visually indicate boos activations

