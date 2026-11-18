"""The async client; cancelling the task raises the SDK's CancelledError."""

from __future__ import annotations

import asyncio

import grow_zipkin as sdk


async def main() -> None:
    async with sdk.AsyncZipkinClient.from_env() as client:
        print(await client.health())
        task = asyncio.create_task(client.ping())
        try:
            print(f"ping: {await asyncio.wait_for(task, timeout=5):.1f} ms")
        except asyncio.TimeoutError:
            print("ping took longer than 5 s and was cancelled")


if __name__ == "__main__":
    asyncio.run(main())
