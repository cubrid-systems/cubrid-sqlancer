package sqlancer.cubrid;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.NoRECOracle;
import sqlancer.common.oracle.TLPWhereOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator;

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
    }
}
