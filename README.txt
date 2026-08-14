=== FORK NOTICE ===
This is a personal experimental fork of ALMA maintained by KuangWei-hash.
It is NOT the official ALMA and is not intended to be merged upstream.

Upstream: https://github.com/A-L-M-A/ALMA
Fork:     https://github.com/KuangWei-hash/ALMA

Purpose of this fork:
Experimenting with an ADAPTOR layer to connect ALMA's affective model to
external systems (e.g. LLMs / APIs) for integration research.
The ADAPTOR module is experimental and not part of official ALMA.
===================


ALMA 2015

This archive contains all sources and libraries for the ALMA suite.

Libraries used for this version:

jama (http://math.nist.gov/javanumerics/jama/)

xmlbeans (https://xmlbeans.apache.org)

processing (https://processing.org)

PeasyCam (http://mrfeinberg.com/peasycam/)

To begin with ALMA have a look at the runtime version in the runtime directory.

For usage on the MAC consider:
To make the processing work, please download the latest version of peocessing
from https://processing.org/download/

Unzip it, copy the following 4 jar files in "core/library":

gluegen-rt.jar
gluegen-rt-natives-macosx-universal.jar
jogl-all.jar
jogl-all-natives-macosx-universal.jar

to "Library/Java/Extensions" on your MAC.



