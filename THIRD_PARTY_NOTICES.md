# Third-party notices

## pyatv

The MRP protobuf definitions under
`protocol/src/main/proto/pyatv/protocols/mrp/protobuf/` and protocol behavior
documented or implemented under `protocol/.../mrp/` are derived from pyatv,
pinned during development to commit
`b277a4c8222ecdcbaab8a24e3e713ca44765adb4` (release 0.18.0).

The wire-neutral mechanical changes to the vendored `.proto` files are Java
code generation options (`java_package` and `java_multiple_files`) and removal
of trailing whitespace. They do not change the protobuf wire format.

pyatv is licensed under the MIT License:

> Copyright (c) 2020 Pierre Ståhl
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in
> all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.
