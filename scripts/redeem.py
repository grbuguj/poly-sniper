#!/usr/bin/env python3
"""
Polymarket CTF Redeem Script
Java에서 ProcessBuilder로 호출
  
Usage:
  python redeem.py <condition_id> [--neg-risk]

Environment Variables (필수):
  POLY_PRIVATE_KEY     - EOA private key
  POLY_API_KEY         - CLOB API key  
  POLY_API_SECRET      - CLOB API secret
  POLY_PASSPHRASE      - CLOB passphrase
  POLY_PROXY_ADDRESS   - Proxy wallet address (funder)
  BUILDER_API_KEY      - Builder API key
  BUILDER_SECRET       - Builder secret
  BUILDER_PASSPHRASE   - Builder passphrase

Output (JSON):
  {"status": "SUCCESS", "tx_id": "...", "message": "..."}
  {"status": "ERROR", "message": "..."}
  {"status": "NOT_RESOLVED", "message": "Condition not yet resolved"}
  {"status": "NO_BALANCE", "message": "No redeemable balance"}
"""

import json
import os
import sys
import traceback


def main():
    if len(sys.argv) < 2:
        result = {"status": "ERROR", "message": "Usage: redeem.py <condition_id> [--neg-risk]"}
        print(json.dumps(result))
        sys.exit(1)

    condition_id = sys.argv[1]
    neg_risk = "--neg-risk" in sys.argv

    # 환경변수 확인
    required_vars = [
        "POLY_PRIVATE_KEY", "POLY_API_KEY", "POLY_API_SECRET", "POLY_PASSPHRASE",
        "POLY_PROXY_ADDRESS", "BUILDER_API_KEY", "BUILDER_SECRET", "BUILDER_PASSPHRASE"
    ]
    missing = [v for v in required_vars if not os.environ.get(v)]
    if missing:
        result = {"status": "ERROR", "message": f"Missing env vars: {', '.join(missing)}"}
        print(json.dumps(result))
        sys.exit(1)

    try:
        from py_builder_relayer_client.client import RelayClient
        from py_builder_signing_sdk.config import BuilderConfig
        from py_builder_signing_sdk.sdk_types import BuilderApiKeyCreds
        from py_clob_client.client import ClobClient
        from poly_web3 import RELAYER_URL, PolyWeb3Service

        # Initialize ClobClient
        host = "https://clob.polymarket.com"
        chain_id = 137

        clob_client = ClobClient(
            host,
            key=os.environ["POLY_PRIVATE_KEY"],
            chain_id=chain_id,
            signature_type=1,  # Proxy wallet
            funder=os.environ["POLY_PROXY_ADDRESS"],
        )
        clob_client.set_api_creds(clob_client.create_or_derive_api_creds())

        # Initialize RelayerClient
        relayer_client = RelayClient(
            RELAYER_URL,
            chain_id,
            os.environ["POLY_PRIVATE_KEY"],
            BuilderConfig(
                local_builder_creds=BuilderApiKeyCreds(
                    key=os.environ["BUILDER_API_KEY"],
                    secret=os.environ["BUILDER_SECRET"],
                    passphrase=os.environ["BUILDER_PASSPHRASE"],
                )
            ),
        )

        # Create service
        service = PolyWeb3Service(clob_client=clob_client, relayer_client=relayer_client)

        # 1. Check if condition is resolved
        try:
            is_resolved = service.is_condition_resolved(condition_id)
        except Exception as e:
            result = {"status": "ERROR", "message": f"Failed to check resolution: {str(e)}"}
            print(json.dumps(result))
            sys.exit(0)

        if not is_resolved:
            result = {"status": "NOT_RESOLVED", "message": "Condition not yet resolved"}
            print(json.dumps(result))
            sys.exit(0)

        # 2. Check redeemable balance
        try:
            redeemable = service.get_redeemable_index_and_balance(condition_id)
            if not redeemable or all(bal == 0 for _, bal in redeemable):
                result = {"status": "NO_BALANCE", "message": "No redeemable balance"}
                print(json.dumps(result))
                sys.exit(0)
        except Exception as e:
            # Balance check failed, but still try to redeem
            pass

        # 3. Execute redeem
        redeem_result = service.redeem(condition_ids=condition_id)

        result = {
            "status": "SUCCESS",
            "tx_id": str(redeem_result.get("transactionID", "")),
            "tx_hash": str(redeem_result.get("transactionHash", "")),
            "state": str(redeem_result.get("state", "")),
            "message": "Redeem executed successfully"
        }
        print(json.dumps(result))

    except Exception as e:
        result = {
            "status": "ERROR",
            "message": str(e),
            "traceback": traceback.format_exc()
        }
        print(json.dumps(result))
        sys.exit(1)


if __name__ == "__main__":
    main()
