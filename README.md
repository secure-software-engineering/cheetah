
Cheetah 

---------------------------------------------------------

A JIT taint analysis for Android applications

Author: Lisa Nguyen Quang Do <lisa.nguyen@iem.fraunhofer.de>

This software is under the EPL licence, for more information, please visit: https://eclipse.org/legal/eplfaq.php

---------------------------------------------------------

To run the analysis:

1. Cheetah is the Plugin project containing the analysis. Run it with the following configurations:
-Dosgi.requiredJavaVersion=1.7 -Xms256m -Xmx1024m -XX:MaxPermSize=512m

2. Once the target Eclipse application is opened, import the demo Android application.

3.  Make sure this Eclipse instance supports Android and that projects are built automatically (Project > Build Automatically) and (Window > Preferences > Android > Build > Uncheck "Skip packaging and dexing...").

4. Configure the Android project to add the layered builder (Configurations > Add Layered Builder)

5. Open the Overview view and the Detail view (Window > Show view)

6. Enjoy.

-------------------------------------------------