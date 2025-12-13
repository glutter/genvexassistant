#!/bin/bash
javac GenvexClient.java GenvexServer.java
if [ $? -eq 0 ]; then
    echo "Starting Genvex Server on port 8080..."
    java GenvexServer
else
    echo "Compilation failed."
fi
