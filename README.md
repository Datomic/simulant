## Simulant

Simulant is a library and schema for developing simulation-based
tests.

## Getting Started

Leiningen dependency:

    [com.datomic/simulant "0.1.8"]

Work through examples/repl/hello_world.clj at a REPL.

## Docs

See the [wiki](https://github.com/Datomic/simulant/wiki).

## Running Tests

From a repl:

    (require :reload '[simulant.examples-test :as et])
    (et/-main)

## License

    Copyright (c) Metadata Partners, LLC. All rights reserved. The use
    and distribution terms for this software are covered by the
    Eclipse Public License 1.0
    (http://opensource.org/licenses/eclipse-1.0.php) which can be
    found in the file epl-v10.html at the root of this
    distribution. By using this software in any fashion, you are
    agreeing to be bound by the terms of this license. You must not
    remove this notice, or any other, from this software.
