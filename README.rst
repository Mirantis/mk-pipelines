============
Mk Pipelines
============

Jenkins Groovy scripts for MCP operation that reuse common `pipeline 
libraries <https://github.com/Mirantis/pipeline-library>`_.

Licensing
=========

Unless specifically noted, all parts of this project are licensed 
under the Apache 2.0 `license <https://github.com/Mirantis/mk-pipelines/LICENSE>`_.


Testing
========

Basic gradle test can be executed by (where 172.18.176.4) is address of DNS server capable to resolve artifacts server

.. code:: bash

  docker run --rm --dns 172.18.176.4 -v $PWD:/usr/bin/app:z niaquinto/gradle check
