#!/usr/bin/env bash
# ==============================================================
# AgriTrace Docker Demo Acceptance Test
#
# Proves that the 3-node AgriTrace Docker Compose environment works:
#   1. Node A (Farmer: 8443), Node B (Carrier: 8444), Node C (Retailer: 8445) reachability
#   2. Consortium network identity verification (/api/v1/network)
#   3. Local administrator authentication (/api/v1/auth/login) on all 3 nodes
#   4. P2P mTLS port security rejection of unauthenticated callers (9443)
#   5. Public QR code traceability generation (/api/v1/public/trace-qr/*)
# ==============================================================
set -euo pipefail

NODE_A_URL="${NODE_A_URL:-https://localhost:8443/AgriTrace}"
NODE_B_URL="${NODE_B_URL:-https://localhost:8444/AgriTrace}"
NODE_C_URL="${NODE_C_URL:-https://localhost:8445/AgriTrace}"
P2P_A_URL="${P2P_A_URL:-https://localhost:9443/AgriTrace}"

ADMIN_A_PASSWORD="${ADMIN_A_PASSWORD:-AdminA@123456}"
ADMIN_B_PASSWORD="${ADMIN_B_PASSWORD:-AdminB@123456}"
ADMIN_C_PASSWORD="${ADMIN_C_PASSWORD:-AdminC@123456}"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

pass_count=0
fail_count=0

test_pass() {
    echo -e "  [${GREEN}PASS${NC}] $1"
    pass_count=$((pass_count + 1))
}

test_fail() {
    echo -e "  [${RED}FAIL${NC}] $1: $2"
    fail_count=$((fail_count + 1))
}

echo -e "${CYAN}=================================================================${NC}"
echo -e "${CYAN} AgriTrace Docker 3-Node Acceptance Test Runner${NC}"
echo -e "${CYAN}=================================================================${NC}"

# 1. Wait for services to respond
echo -e "\n${YELLOW}[1/5] Checking Node Reachability...${NC}"
for item in "Node A|$NODE_A_URL" "Node B|$NODE_B_URL" "Node C|$NODE_C_URL"; do
    IFS='|' read -r name url <<< "$item"
    echo -n "  Waiting for $name ($url) ... "
    ready=0
    for i in {1..30}; do
        code=$(curl -k -s -o /dev/null -w "%{http_code}" "$url/" || true)
        if [ "$code" -eq 200 ] || [ "$code" -eq 302 ]; then
            ready=1
            break
        fi
        sleep 2
    done
    if [ "$ready" -eq 1 ]; then
        echo -e "${GREEN}UP (HTTP $code)${NC}"
        test_pass "$name web endpoint reachable"
    else
        echo -e "${RED}TIMEOUT${NC}"
        test_fail "$name web endpoint reachable" "Failed to respond after 60s"
    fi
done

# 2. Verify Consortium Network Identity
echo -e "\n${YELLOW}[2/5] Verifying Consortium Network Identity...${NC}"
for item in "Node A|$NODE_A_URL" "Node B|$NODE_B_URL" "Node C|$NODE_C_URL"; do
    IFS='|' read -r name url <<< "$item"
    resp=$(curl -k -s "$url/api/v1/network" || true)
    network_id=$(echo "$resp" | grep -o '"networkId":"[^"]*' | cut -d'"' -f4 || echo "")
    if [ "$network_id" == "agritrace-docker-demo-v1" ]; then
        test_pass "$name networkId matches '$network_id'"
    else
        test_fail "$name networkId matches" "Expected 'agritrace-docker-demo-v1', got '$network_id' (Response: $resp)"
    fi
done

# 3. Authenticate local administrator accounts
echo -e "\n${YELLOW}[3/5] Testing Local Administrator Authentication...${NC}"
for item in "Node A|$NODE_A_URL|admin-a|$ADMIN_A_PASSWORD" "Node B|$NODE_B_URL|admin-b|$ADMIN_B_PASSWORD" "Node C|$NODE_C_URL|admin-c|$ADMIN_C_PASSWORD"; do
    IFS='|' read -r name url user pass <<< "$item"
    login_payload=$(printf '{"username":"%s","password":"%s"}' "$user" "$pass")
    resp=$(curl -k -s -X POST -H "Content-Type: application/json" -d "$login_payload" "$url/api/v1/auth/login" || true)
    role=$(echo "$resp" | grep -o '"role":"[^"]*' | cut -d'"' -f4 || echo "")
    if [ "$role" == "ADMIN" ]; then
        test_pass "$name: login succeeded for $user (role: $role)"
    else
        test_fail "$name: login for $user" "Expected role ADMIN, got '$role' (Response: $resp)"
    fi
done

# 4. Verify P2P mTLS Port Security
echo -e "\n${YELLOW}[4/5] Testing P2P mTLS Port Security...${NC}"
# Connect to P2P port 9443 without client certificate; should fail handshake or be rejected
mtls_rejected=0
p2p_resp=$(curl -k -s -w "%{http_code}" -o /dev/null "$P2P_A_URL/api/v1/internal/p2p/blocks" 2>&1 || true)
# Either curl fails with TLS handshake / alert (exit non-zero) or returns 401/403/blank
if [ "$p2p_resp" == "000" ] || [ "$p2p_resp" == "401" ] || [ "$p2p_resp" == "403" ]; then
    mtls_rejected=1
fi

if [ "$mtls_rejected" -eq 1 ]; then
    test_pass "P2P port 9443 strictly enforces client certificate authentication"
else
    test_fail "P2P port 9443 security" "Expected reject without client cert, got HTTP $p2p_resp"
fi

# 5. Public Trace QR generation
echo -e "\n${YELLOW}[5/5] Testing Public QR Trace Endpoint...${NC}"
qr_code=$(curl -k -s -w "%{http_code}" -o /tmp/trace_test_qr.svg "$NODE_A_URL/api/v1/public/trace-qr/DEMO-BATCH-001" || true)
if [ "$qr_code" -eq 200 ] && grep -q "<svg" /tmp/trace_test_qr.svg 2>/dev/null; then
    test_pass "Public QR SVG generation returns HTTP 200 with valid SVG data"
else
    test_fail "Public QR SVG generation" "Expected HTTP 200 and SVG content, got HTTP $qr_code"
fi
rm -f /tmp/trace_test_qr.svg 2>/dev/null || true

# Summary
echo -e "\n${CYAN}=================================================================${NC}"
echo -e "${CYAN} Acceptance Test Results: ${GREEN}$pass_count PASSED${NC}, ${RED}$fail_count FAILED${NC}"
echo -e "${CYAN}=================================================================${NC}"

if [ "$fail_count" -gt 0 ]; then
    exit 1
fi
exit 0
