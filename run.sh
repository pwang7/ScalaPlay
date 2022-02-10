#! /bin/sh

set -o errexit
set -o nounset
set -o xtrace

CI_ENV="${CI_ENV:-false}"
export MILL_VERSION="0.10.7"

if [ ! -f mill ]; then
  curl -L https://github.com/com-lihaoyi/mill/releases/download/$MILL_VERSION/$MILL_VERSION > mill && chmod +x mill
fi

MILL="./mill --no-server"
$MILL version

# Generate IDEA config
# $MILL mill.scalalib.GenIdea/idea

# Run build and simulation
$MILL play.test
#$MILL play.runMain rdma.PlaySim
#$MILL play.test.testSim rdma.FragmentStreamForkQueryJoinRespTest
#$MILL play.test.testSim rdma.FragmentStreamJoinStreamTest
#$MILL play.test.testSim rdma.StreamAddHeaderTest
#$MILL play.test.testSim rdma.StreamRemoveHeaderTest
#$MILL play.test.testSim rdma.StreamSegmentTest
#$MILL play.test.testSim rdma.StreamDropHeaderTest
#$MILL play.test.testSim rdma.StreamReqAndRespTest
#$MILL play.test.testSim rdma.StreamCounterSourceTest
#$MILL play.test.testSim rdma.StreamExtractCompanyTest

# Check format and lint
if [ "$CI_ENV" = "true" ]; then
  $MILL play.checkFormat
  $MILL play.fix --check
else
  $MILL mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
  $MILL play.fix
fi

