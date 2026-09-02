package sqlancer.cubrid;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.NoRECOracle;
import sqlancer.common.oracle.TLPWhereOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator;
import sqlancer.cubrid.oracle.tlp.CUBRIDTLPAggregateOracle;
import sqlancer.cubrid.oracle.tlp.CUBRIDTLPDistinctOracle;
import sqlancer.cubrid.oracle.tlp.CUBRIDTLPGroupByOracle;
import sqlancer.cubrid.oracle.tlp.CUBRIDTLPHavingOracle;

public enum CUBRIDOracleFactory implements OracleFactory<CUBRIDProvider.CUBRIDGlobalState> {
    WHERE {
        @Override
        public TestOracle<CUBRIDProvider.CUBRIDGlobalState> create(CUBRIDProvider.CUBRIDGlobalState globalState)
                throws SQLException {
            CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState);
            ExpectedErrors expectedErrors = ExpectedErrors.newErrors().with(CUBRIDErrors.getExpressionErrors()).build();

            return new TLPWhereOracle<>(globalState, gen, expectedErrors);
        }
    },
    NOREC {
        @Override
        public TestOracle<CUBRIDProvider.CUBRIDGlobalState> create(CUBRIDProvider.CUBRIDGlobalState globalState)
                throws Exception {
            CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState);
            ExpectedErrors errors = ExpectedErrors.newErrors().with(CUBRIDErrors.getExpressionErrors()).build();
            return new NoRECOracle<>(globalState, gen, errors);
        }
    },
    GROUP_BY {
        @Override
        public TestOracle<CUBRIDProvider.CUBRIDGlobalState> create(CUBRIDProvider.CUBRIDGlobalState globalState) {
            return new CUBRIDTLPGroupByOracle(globalState);
        }
    },
    DISTINCT {
        @Override
        public TestOracle<CUBRIDProvider.CUBRIDGlobalState> create(CUBRIDProvider.CUBRIDGlobalState globalState) {
            return new CUBRIDTLPDistinctOracle(globalState);
        }
    },
    AGGREGATE {
        @Override
        public TestOracle<CUBRIDProvider.CUBRIDGlobalState> create(CUBRIDProvider.CUBRIDGlobalState globalState) {
            return new CUBRIDTLPAggregateOracle(globalState);
        }
    },
    HAVING {
        @Override
        public TestOracle<CUBRIDProvider.CUBRIDGlobalState> create(CUBRIDProvider.CUBRIDGlobalState globalState) {
            return new CUBRIDTLPHavingOracle(globalState);
        }
    }
}
